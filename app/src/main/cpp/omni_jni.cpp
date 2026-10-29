// JNI bridge: llama.cpp + libmtmd for LFM3-Omni (text, image and audio input, text output).
// All calls except nativeAbort() must come from one thread.

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <algorithm>
#include <map>
#include <mutex>
#include <unistd.h>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "sampling.h"
#include "mmproj_q8.h"

#define TAG "omni"
#define LOGI(...) omni_logf(ANDROID_LOG_INFO,  __VA_ARGS__)
#define LOGE(...) omni_logf(ANDROID_LOG_ERROR, __VA_ARGS__)

// Session log file for the in-app Logs page. Flushed on every write so the lines before a native crash survive.
static std::mutex g_log_mutex;
static FILE *     g_log_file    = nullptr;
static long       g_log_bytes   = 0;
static bool       g_log_newline = true;
constexpr long    LOG_MAX_BYTES = 8L << 20;

static void log_file_write(int prio, const char * text) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (!g_log_file || g_log_bytes > LOG_MAX_BYTES) return;
    if (g_log_newline) {
        timespec ts{};
        clock_gettime(CLOCK_REALTIME, &ts);
        tm t{};
        localtime_r(&ts.tv_sec, &t);
        const char lvl = prio >= ANDROID_LOG_ERROR ? 'E' : prio == ANDROID_LOG_WARN ? 'W' : prio == ANDROID_LOG_INFO ? 'I' : 'D';
        g_log_bytes += fprintf(g_log_file, "%02d:%02d:%02d.%03ld %c ", t.tm_hour, t.tm_min, t.tm_sec, ts.tv_nsec / 1000000, lvl);
    }
    g_log_bytes += fprintf(g_log_file, "%s", text);
    const size_t n = strlen(text);
    g_log_newline = n > 0 && text[n - 1] == '\n';
    if (g_log_bytes > LOG_MAX_BYTES) fprintf(g_log_file, "\n[log truncated at %ld MB]\n", LOG_MAX_BYTES >> 20);
    fflush(g_log_file);
}

static void omni_log(int prio, const char * text) {
    __android_log_write(prio, TAG, text);
    log_file_write(prio, text);
}

__attribute__((format(printf, 2, 3)))
static void omni_logf(int prio, const char * fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf) - 1, fmt, ap);
    va_end(ap);
    const size_t n = strlen(buf);
    if (n == 0 || buf[n - 1] != '\n') strcat(buf, "\n");
    omni_log(prio, buf);
}

constexpr int N_BATCH = 512;

static llama_model               * g_model   = nullptr;
static llama_context             * g_ctx     = nullptr;
static mtmd::context_ptr           g_mtmd;
static common_chat_templates_ptr   g_tmpls;
static common_sampler            * g_smpl    = nullptr;
static common_batch                g_batch;
static std::string                 g_device_desc;

static std::vector<common_chat_msg> g_history;
static std::vector<common_chat_tool> g_tools;     // rendered into the system turn as <tools>
static mtmd::bitmaps                g_pending;     // media attached to the next user message
static llama_pos                    g_n_past     = 0;
static int                          g_n_left     = 0;
static std::string                  g_utf8_cache;
static std::string                  g_reply;
static std::atomic<bool>            g_abort{false};

// Saved document state: the next prompt's opening (system turn + documents, up to g_cache_split) is loaded from
// g_cache_path instead of being read again, or read once and saved there. Set by nativeSetPromptCache, used once.
static std::string                  g_cache_path;
static std::string                  g_cache_split;
static bool                         g_cache_first = false; // split at the first occurrence (the system turn) instead of the last

// voice replies: LFM3 continues the conversation in "mixed" mode, interleaving text tokens and speech frames
static mtmd_helper_gen_audio *      g_voice        = nullptr;
static std::vector<float>           g_voice_h;              // hidden state fed to the speech decoder
static llama_token                  g_voice_tok    = LLAMA_TOKEN_NULL;
static std::string                  g_voice_text;           // text since the last nativeVoiceText()
static std::vector<float>           g_voice_pcm;            // speech since the last nativeVoiceAudio()
static int                          g_voice_rate   = 0;
static int                          g_voice_left   = 0;
static bool                         g_voice_active = false;

// Spoken replies in a chosen voice: LFM3's TTS mode ("Perform TTS in <language> using a <male|female> voice"),
// which the mixed text+speech mode lacks. It runs in a small context of its own so the chat's cache stays intact.
static llama_context *               g_tts_ctx    = nullptr;
static mtmd_helper_gen_audio *       g_tts        = nullptr;
static common_sampler *              g_tts_smpl   = nullptr;
static std::vector<float>            g_tts_h;
static llama_token                   g_tts_tok    = LLAMA_TOKEN_NULL;
static int                           g_tts_left   = 0;
static bool                          g_tts_active = false;
static int                           g_threads    = 4;

static void tts_free() {
    if (g_tts)      { mtmd_helper_gen_audio_free(g_tts); g_tts = nullptr; }
    if (g_tts_smpl) { common_sampler_free(g_tts_smpl); g_tts_smpl = nullptr; }
    if (g_tts_ctx)  { llama_free(g_tts_ctx); g_tts_ctx = nullptr; }
    g_tts_active = false;
}

// With GGML_HEXAGON_PROFILE=1 the Hexagon backend reports every NPU op; summed here per op so a reply produces
// a short table (nativeProfileDump) instead of thousands of log lines.
struct prof_entry { uint64_t usec = 0; uint32_t count = 0; };
static std::mutex                         g_prof_mutex;
static std::map<std::string, prof_entry>  g_prof;
static prof_entry                         g_prof_batches;

static bool prof_collect(const char * text) {
    const char * p = strstr(text, "profile-op ");
    if (!p) return false;
    p += strlen("profile-op ");
    const char * u = strstr(p, "|usec ");
    if (!u) return true;
    const uint64_t usec = strtoull(u + 6, nullptr, 10);
    std::lock_guard<std::mutex> lock(g_prof_mutex);
    if (strncmp(p, "OPBATCH", 7) == 0) {
        g_prof_batches.usec += usec;
        g_prof_batches.count++;
    } else {
        auto & e = g_prof[std::string(p, u - p)];
        e.usec += usec;
        e.count++;
    }
    return true;
}

static void log_cb(ggml_log_level level, const char * text, void *) {
    if (prof_collect(text)) return;
    static int last_prio = ANDROID_LOG_DEBUG;
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        case GGML_LOG_LEVEL_CONT:  prio = last_prio;         break;
        default: break;
    }
    last_prio = prio;
    __android_log_write(prio, TAG, text);
    // debug output (graph dumps, per-tensor listings) runs to megabytes; keep the copyable log to info and up
    if (prio > ANDROID_LOG_DEBUG) log_file_write(prio, text);
}

static bool abort_cb(void *) { return g_abort.load(); }

// Last backend exception, e.g. a GPU driver failing to compile a kernel. Read by nativeLastError().
static std::string g_last_error;

static void backend_failed(const char * where, const std::exception & e) {
    g_last_error = e.what();
    LOGE("%s: backend exception: %s", where, e.what());
}

static std::string jstr(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// returns true when s ends in the middle of a UTF-8 sequence
static bool utf8_incomplete(const std::string & s) {
    for (int i = 1; i <= 4 && i <= (int) s.size(); i++) {
        const unsigned char c = s[s.size() - i];
        if ((c & 0xC0) == 0x80) continue;           // continuation byte
        int need = 1;
        if      ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return need > i;
    }
    return false;
}

// Same as common_chat_format_single(), but with enable_thinking = false:
// LFM3 is non-reasoning and expects every assistant turn to open with an empty <think></think>.
static std::string format_turn(const common_chat_msg & new_msg, bool add_generation_prompt = true) {
    common_chat_templates_inputs inputs;
    inputs.use_jinja       = true;
    inputs.enable_thinking = false;

    inputs.messages = g_history;
    inputs.tools    = g_tools;
    // past = what the KV cache already holds; with nothing decoded yet, render everything, including the system message
    std::string past;
    if (g_n_past > 0 && !g_history.empty()) {
        inputs.add_generation_prompt = false;
        past = common_chat_templates_apply(g_tmpls.get(), inputs).prompt.str();
    }
    inputs.messages.push_back(new_msg);
    inputs.add_generation_prompt = add_generation_prompt;
    const std::string full = common_chat_templates_apply(g_tmpls.get(), inputs).prompt.str();

    // the reply was cut at <|im_end|>, so the newline after it is not in the KV cache yet
    std::string out = (!past.empty() && past.back() == '\n') ? "\n" : "";
    return out + full.substr(past.size());
}

// text-only output: never let the model switch to its audio or interleaved modes
static std::vector<llama_logit_bias> text_only_bias() {
    std::vector<llama_logit_bias> bias;
    for (const char * piece : { "<|audio_start|>", "<|mixed_start|>" }) {
        auto toks = common_tokenize(llama_model_get_vocab(g_model), piece, false, true);
        if (toks.size() == 1) bias.push_back({ toks[0], -INFINITY });
    }
    return bias;
}

static ggml_backend_dev_t find_device(const std::string & name) {
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto * dev = ggml_backend_dev_get(i);
        if (name == ggml_backend_dev_name(dev)) return dev;
    }
    return nullptr;
}

static void free_all() {
    tts_free();
    g_history.clear();
    g_pending.entries.clear();
    g_n_past = 0;
    if (g_voice) { mtmd_helper_gen_audio_free(g_voice); g_voice = nullptr; }
    g_voice_active = false;
    if (g_smpl)  { common_sampler_free(g_smpl); g_smpl = nullptr; }
    g_batch = common_batch();
    g_tmpls.reset();
    g_mtmd.reset();
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

// The opening of a fresh chat's first prompt (system turn and documents): loaded from [path] when it holds exactly
// these tokens, else evaluated and saved there. 0 = done (g_n_past moved), 1 = skipped (nothing changed), 3 = aborted.
static int prompt_prefix(const std::string & prefix, bool add_special, const std::string & path) {
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const std::vector<llama_token> toks = common_tokenize(vocab, prefix, add_special, true);
    if (toks.empty() || (llama_pos) toks.size() + 512 > (llama_pos) llama_n_ctx(g_ctx)) return 1;

    std::vector<llama_token> saved(toks.size() + 1);
    size_t n_saved = 0;
    FILE * probe = fopen(path.c_str(), "rb");
    if (probe) {
        fclose(probe);
        const int64_t t0 = ggml_time_us();
        if (llama_state_seq_load_file(g_ctx, path.c_str(), 0, saved.data(), saved.size(), &n_saved) > 0 && n_saved == toks.size() &&
            std::equal(toks.begin(), toks.end(), saved.begin())) {
            g_n_past = (llama_pos) n_saved;
            LOGI("document state loaded: %zu tokens in %lld ms (not read again)", n_saved, (long long) ((ggml_time_us() - t0) / 1000));
            return 0;
        }
        LOGI("saved document state doesn't match, reading it again");
        llama_memory_clear(llama_get_memory(g_ctx), true);
        remove(path.c_str());
    }

    const int64_t t0 = ggml_time_us();
    for (size_t i = 0; i < toks.size(); i += N_BATCH) {
        const int32_t n = (int32_t) std::min<size_t>(N_BATCH, toks.size() - i);
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(toks.data() + i), n);
        const int rc = llama_decode(g_ctx, batch);
        if (rc != 0) {
            llama_memory_clear(llama_get_memory(g_ctx), true);
            return g_abort ? 3 : 1;
        }
    }
    g_n_past = (llama_pos) toks.size();
    const int64_t t1 = ggml_time_us();
    const std::string tmp = path + ".part";
    const size_t bytes = llama_state_seq_save_file(g_ctx, tmp.c_str(), 0, toks.data(), toks.size());
    if (bytes > 0 && rename(tmp.c_str(), path.c_str()) == 0) {
        LOGI("document read: %zu tokens in %lld ms, saved (%zu MB) in %lld ms", toks.size(), (long long) ((t1 - t0) / 1000), bytes >> 20,
             (long long) ((ggml_time_us() - t1) / 1000));
    } else {
        remove(tmp.c_str());
        LOGI("document read: %zu tokens in %lld ms (saving its state failed)", toks.size(), (long long) ((t1 - t0) / 1000));
    }
    return 0;
}

extern "C" {

// The next nativePrompt saves/loads its opening up to the last [split], or the first with [first] (see prompt_prefix).
// Empty path = off.
JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeSetPromptCache(JNIEnv * env, jobject, jstring jpath, jstring jsplit, jboolean first) {
    g_cache_path  = jstr(env, jpath);
    g_cache_split = jstr(env, jsplit);
    g_cache_first = first;
}

JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeOpenLog(JNIEnv * env, jobject, jstring jpath) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_log_file) fclose(g_log_file);
    g_log_file    = fopen(jstr(env, jpath).c_str(), "a");
    g_log_bytes   = 0;
    g_log_newline = true;
    // ggml-vulkan reports the name of a pipeline that fails to compile on std::cerr, which Android drops
    if (g_log_file) dup2(fileno(g_log_file), STDERR_FILENO);
}

// Kotlin-side events go into the same file, interleaved in order with the native lines
JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeLog(JNIEnv * env, jobject, jint prio, jstring jmsg) {
    std::string msg = jstr(env, jmsg);
    if (msg.empty() || msg.back() != '\n') msg += '\n';
    log_file_write(prio, msg.c_str());
}

JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeInit(JNIEnv * env, jobject, jstring lib_dir) {
    llama_log_set(log_cb, nullptr);
    mtmd_helper_log_set(log_cb, nullptr);
    ggml_backend_load_all_from_path(jstr(env, lib_dir).c_str());
    llama_backend_init();
}

// "name\tdescription" for every non-CPU device
JNIEXPORT jobjectArray JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeDevices(JNIEnv * env, jobject) {
    std::vector<std::string> out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto * dev = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_CPU) continue;
        out.push_back(std::string(ggml_backend_dev_name(dev)) + "\t" + ggml_backend_dev_description(dev));
    }
    jobjectArray arr = env->NewObjectArray((jsize) out.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < out.size(); i++) {
        env->SetObjectArrayElement(arr, (jsize) i, env->NewStringUTF(out[i].c_str()));
    }
    return arr;
}

// device: ggml device name, or "" for CPU only. Returns null on success, error text otherwise.
JNIEXPORT jstring JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeLoad(JNIEnv * env, jobject, jstring jmodel, jstring jmmproj,
                                                  jstring jdevice, jint n_ctx, jint n_threads, jboolean media_gpu, jint flash_attn) {
    free_all();
    const std::string model_path  = jstr(env, jmodel);
    const std::string mmproj_path = jstr(env, jmmproj);
    const std::string dev_name    = jstr(env, jdevice);

    ggml_backend_dev_t dev = dev_name.empty() ? nullptr : find_device(dev_name);
    if (!dev_name.empty() && !dev) {
        return env->NewStringUTF(("device not found: " + dev_name).c_str());
    }
    ggml_backend_dev_t devs[2] = { dev, nullptr };

    llama_model_params mparams = llama_model_default_params();
    mparams.devices      = devs;
    mparams.n_gpu_layers = dev ? -1 : 0;
    try {
        g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    } catch (const std::exception & e) {
        backend_failed("model load", e); // e.g. the Hexagon NPU refusing a FastRPC session
    }
    if (!g_model) return env->NewStringUTF(("failed to load model " + g_last_error).c_str());

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = n_ctx;
    cparams.n_batch         = N_BATCH;
    cparams.n_ubatch        = N_BATCH;
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;
    g_threads               = n_threads;
    // off: with it on, every prompt token is made an output and gets the full 128k-word output layer, which made
    // long documents far slower; the mixed voice mode switches it on just for its turn
    cparams.embeddings      = false;
    cparams.flash_attn_type = (llama_flash_attn_type) flash_attn;
    // LFM3's 5 sliding-window layers only ever look back 256 tokens; a full-size cache for them would nearly double
    // the KV memory (16k: 352 -> 192 MB, 128k: 2.75 -> 1.5 GB). We only append, so the small window cache is enough.
    cparams.swa_full        = false;

    mtmd_context_params vparams = mtmd_context_params_default();
    // the image/audio encoder can crash on Adreno GPU drivers, so it runs on the CPU unless asked
    const bool enc_gpu      = dev != nullptr && media_gpu;
    vparams.use_gpu         = enc_gpu;
    vparams.device          = enc_gpu ? dev : nullptr;
    vparams.n_threads       = n_threads;
    vparams.print_timings   = true;
    vparams.warmup          = true;
    // flash attention may be off for the GPU text model, but a CPU encoder needs it: without it the image
    // warmup alone reserves ~4.5 GB for the attention matrix
    vparams.flash_attn_type = enc_gpu ? cparams.flash_attn_type : LLAMA_FLASH_ATTN_TYPE_AUTO;
    // non-causal image attention needs the whole image in one ubatch
    auto mem = mtmd_get_memory_usage(mmproj_path.c_str(), vparams);
    if (mem.use_non_causal && mem.image_max_tokens > N_BATCH) {
        vparams.image_max_tokens = N_BATCH;
        vparams.image_min_tokens = std::min(vparams.image_min_tokens, N_BATCH);
    }

    try {
        g_ctx = llama_init_from_model(g_model, cparams);
    } catch (const std::exception & e) {
        backend_failed("init", e);
    }
    if (!g_ctx) { free_all(); return env->NewStringUTF(("failed to create context " + g_last_error).c_str()); }
    llama_set_abort_callback(g_ctx, abort_cb, nullptr);

    try {
        g_mtmd.reset(mtmd_init_from_file(mmproj_path.c_str(), g_model, vparams));
    } catch (const std::exception & e) {
        backend_failed("mtmd_init", e);
    }
    if (!g_mtmd) { free_all(); return env->NewStringUTF("failed to load mmproj"); }

    g_tmpls = common_chat_templates_init(g_model, "");
    g_batch = common_batch(g_ctx);
    g_device_desc = dev ? std::string(ggml_backend_dev_name(dev)) + " (" + ggml_backend_dev_description(dev) + ")"
                        : "CPU x" + std::to_string(n_threads);
    LOGI("media encoder on %s, flash attention %d", enc_gpu ? "GPU" : "CPU", flash_attn);
    LOGI("loaded on %s", g_device_desc.c_str());
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeUnload(JNIEnv *, jobject) {
    free_all();
}

JNIEXPORT jstring JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeDeviceInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(g_device_desc.c_str());
}

// {vision, audio, audio sample rate}
JNIEXPORT jintArray JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeCaps(JNIEnv * env, jobject) {
    jint caps[3] = {0, 0, 0};
    if (g_mtmd) {
        caps[0] = mtmd_support_vision(g_mtmd.get());
        caps[1] = mtmd_support_audio(g_mtmd.get());
        caps[2] = mtmd_get_audio_sample_rate(g_mtmd.get());
    }
    jintArray arr = env->NewIntArray(3);
    env->SetIntArrayRegion(arr, 0, 3, caps);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeReset(JNIEnv * env, jobject, jstring jsystem) {
    g_history.clear();
    g_pending.entries.clear();
    g_n_past = 0;
    if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), true);
    const std::string sys = jstr(env, jsystem);
    if (!sys.empty()) {
        common_chat_msg msg;
        msg.role    = "system";
        msg.content = sys;
        g_history.push_back(msg); // sent with the first user message
    }
}

// Adds one callable tool (name, description, JSON-schema parameters); an empty name clears the list. Takes effect
// from the next nativeReset, since the tool list lives in the system turn.
JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeSetTool(JNIEnv * env, jobject, jstring jname, jstring jdesc, jstring jparams) {
    const std::string name = jstr(env, jname);
    if (name.empty()) { g_tools.clear(); return; }
    common_chat_tool t;
    t.name        = name;
    t.description = jstr(env, jdesc);
    t.parameters  = jstr(env, jparams);
    g_tools.push_back(t);
}

// Earlier turns of a reopened chat, as text. Call right after nativeReset: nothing is decoded yet, so the whole
// history is rendered and evaluated together with the next user message.
JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeSetHistory(JNIEnv * env, jobject, jobjectArray roles, jobjectArray contents) {
    const jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(roles, i);
        auto jc = (jstring) env->GetObjectArrayElement(contents, i);
        common_chat_msg msg;
        msg.role    = jstr(env, jr);
        msg.content = jstr(env, jc);
        g_history.push_back(msg);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }
    LOGI("history restored: %d messages", (int) n);
}

// rgb: packed RGB888, w x h
JNIEXPORT jboolean JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeAddImage(JNIEnv * env, jobject, jbyteArray rgb, jint w, jint h) {
    if (env->GetArrayLength(rgb) != w * h * 3) return false;
    jbyte * data = env->GetByteArrayElements(rgb, nullptr);
    mtmd_bitmap * bmp = mtmd_bitmap_init(w, h, reinterpret_cast<const unsigned char *>(data));
    env->ReleaseByteArrayElements(rgb, data, JNI_ABORT);
    if (!bmp) return false;
    g_pending.entries.emplace_back(bmp);
    LOGI("image attached: %dx%d", w, h);
    return true;
}

// pcm: mono F32 at the model sample rate
JNIEXPORT jboolean JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeAddAudio(JNIEnv * env, jobject, jfloatArray pcm) {
    const jsize n = env->GetArrayLength(pcm);
    if (n == 0) return false;
    jfloat * data = env->GetFloatArrayElements(pcm, nullptr);
    mtmd_bitmap * bmp = mtmd_bitmap_init_from_audio(n, data);
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    if (!bmp) return false;
    g_pending.entries.emplace_back(bmp);
    LOGI("audio attached: %d samples", (int) n);
    return true;
}

// Evaluate one user message with the pending media.
// Returns {status, n_prompt_tokens, n_past}; status 0 = ok, 1 = tokenize error, 2 = eval error, 3 = aborted, 4 = context full
JNIEXPORT jintArray JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativePrompt(JNIEnv * env, jobject, jstring jrole, jstring jtext, jfloat temp, jint max_tokens) {
    jint res[3] = {0, 0, 0};
    auto ret = [&]() {
        jintArray arr = env->NewIntArray(3);
        env->SetIntArrayRegion(arr, 0, 3, res);
        return arr;
    };
    g_abort = false;

    std::string content;
    for (size_t i = 0; i < g_pending.entries.size(); i++) content += mtmd_default_marker();
    content += jstr(env, jtext);

    common_chat_msg msg;
    msg.role    = jstr(env, jrole); // "user", or "tool" for a tool result
    msg.content = content;
    const bool add_bos = g_n_past == 0;
    std::string prompt = format_turn(msg);

    // the jinja template may already render the BOS text; do not add a second one
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const std::string bos_text = common_token_to_piece(vocab, llama_vocab_bos(vocab), true);
    bool add_special = add_bos && !(bos_text.size() && prompt.rfind(bos_text, 0) == 0);

    llama_pos prefix_n = 0; // tokens of the opening loaded or read by prompt_prefix
    const std::string cache_path  = g_cache_path;
    const std::string cache_split = g_cache_split;
    g_cache_path.clear();
    g_cache_split.clear();
    // documents sit after the media markers, so their opening can only be reused without media; the system turn
    // comes before any marker
    if (!cache_path.empty() && g_n_past == 0 && (g_cache_first || g_pending.entries.empty())) {
        const size_t at = g_cache_first ? prompt.find(cache_split) : prompt.rfind(cache_split);
        if (at != std::string::npos && at > 0) {
            const std::string prefix = prompt.substr(0, at + cache_split.size());
            try {
                const int r = prompt_prefix(prefix, add_special, cache_path);
                if (r == 3) { res[0] = 3; return ret(); }
                if (r == 0) {
                    prefix_n    = g_n_past;
                    prompt      = prompt.substr(prefix.size());
                    add_special = false;
                }
            } catch (const std::exception & e) {
                backend_failed("prompt", e);
                res[0] = 5;
                return ret();
            }
        }
    }
    mtmd_input_text text = { prompt.c_str(), prompt.size(), add_special, true };
    auto bitmaps = g_pending.c_ptr();
    mtmd::input_chunks chunks(mtmd_input_chunks_init());
    const int32_t tok_res = mtmd_tokenize(g_mtmd.get(), chunks.ptr.get(), &text, bitmaps.data(), bitmaps.size());
    g_pending.entries.clear();
    if (tok_res != 0) {
        LOGE("mtmd_tokenize failed: %d", tok_res);
        res[0] = 1;
        return ret();
    }

    const size_t n_tokens = mtmd_helper_get_n_tokens(chunks.ptr.get());
    res[1] = (jint) n_tokens;
    LOGI("prompt: %zu chunks, %zu tokens, n_past %d", mtmd_input_chunks_size(chunks.ptr.get()), n_tokens, (int) g_n_past);
    res[1] = (jint) (n_tokens + prefix_n);
    if (g_n_past + (llama_pos) n_tokens + 16 > (llama_pos) llama_n_ctx(g_ctx)) {
        res[0] = 4;
        return ret();
    }

    llama_pos new_n_past = g_n_past;
    int32_t eval_res;
    try {
        eval_res = mtmd_helper_eval_chunks(g_mtmd.get(), g_ctx, chunks.ptr.get(), g_n_past, 0, N_BATCH, true, &new_n_past);
    } catch (const std::exception & e) {
        backend_failed("prompt", e);
        res[0] = 5;
        return ret();
    }
    if (eval_res != 0) {
        LOGE("mtmd_helper_eval_chunks failed: %d", eval_res);
        // KV state is unknown now, the chat must restart
        res[0] = g_abort ? 3 : 2;
        return ret();
    }
    LOGI("prompt evaluated, n_past %d", (int) new_n_past);
    g_n_past = new_n_past;
    g_history.push_back(msg);

    common_params_sampling sparams;
    sparams.temp       = temp;
    sparams.logit_bias = text_only_bias();
    if (g_smpl) common_sampler_free(g_smpl);
    g_smpl = common_sampler_init(g_model, sparams);

    g_n_left = max_tokens;
    g_utf8_cache.clear();
    g_reply.clear();
    res[2] = g_n_past;
    return ret();
}

// Next piece of text. Returns null when the turn is over; "" when a token is buffered (partial UTF-8).
JNIEXPORT jstring JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeNext(JNIEnv * env, jobject) {
    if (g_abort || g_n_left <= 0 || g_n_past + 1 >= (llama_pos) llama_n_ctx(g_ctx)) return nullptr;

    const llama_token tok = common_sampler_sample(g_smpl, g_ctx, -1);
    common_sampler_accept(g_smpl, tok, true);
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), tok)) return nullptr;

    g_batch.clear();
    g_batch.add(tok, g_n_past, 0, true);
    try {
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
            LOGE("decode failed");
            return nullptr;
        }
    } catch (const std::exception & e) {
        backend_failed("decode", e);
        return nullptr;
    }
    g_n_past++;
    g_n_left--;

    g_utf8_cache += common_token_to_piece(g_ctx, tok, false);
    if (utf8_incomplete(g_utf8_cache)) return env->NewStringUTF("");
    g_reply += g_utf8_cache;
    jstring out = env->NewStringUTF(g_utf8_cache.c_str());
    g_utf8_cache.clear();
    return out;
}

// ---- voice replies --------------------------------------------------------------------------------------------

static void voice_collect_audio() {
    int32_t rate = 0; const char * data = nullptr; size_t len = 0; int64_t n = 0;
    if (mtmd_helper_gen_audio_get_output(g_voice, &rate, &data, &len, &n) == 0 && len > 0) {
        const float * f = reinterpret_cast<const float *>(data);
        g_voice_pcm.insert(g_voice_pcm.end(), f, f + len / sizeof(float));
    }
}

static void voice_finish() {
    if (!g_voice_active) return;
    mtmd_helper_gen_audio_flush(g_voice);
    voice_collect_audio();
    g_voice_active = false;
    llama_set_embeddings(g_ctx, false);
    // the helper decoded the reply's text tokens and speech frames itself
    g_n_past = llama_memory_seq_pos_max(llama_get_memory(g_ctx), 0) + 1;
}

// Like nativePrompt, but the reply is spoken: the user turn (with its pending audio/images) is evaluated here,
// then the assistant turn is opened in LFM3's mixed text+speech mode. Returns {status, promptTokens, nPast};
// status 6 = this model can't speak.
JNIEXPORT jintArray JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeVoicePrompt(JNIEnv * env, jobject, jstring jtext, jfloat temp, jint max_steps) {
    jint res[3] = {0, 0, 0};
    auto ret = [&]() {
        jintArray arr = env->NewIntArray(3);
        env->SetIntArrayRegion(arr, 0, 3, res);
        return arr;
    };
    g_abort = false;
    // every early return below leaves the turn unstarted: embeddings back off, or every later text prompt would
    // compute an output row per token (the slowdown that made long documents crawl)
    struct embd_guard { bool keep = false; ~embd_guard() { if (!keep && g_ctx) llama_set_embeddings(g_ctx, false); } } guard;
    try {
        llama_set_embeddings(g_ctx, true); // the speech decoder reads the last hidden state
        if (!g_voice) g_voice = mtmd_helper_gen_audio_init(g_ctx, g_mtmd.get());
        if (!g_voice || mtmd_gen_audio_get_info(g_mtmd.get()).type == MTMD_GEN_AUDIO_TYPE_NONE) {
            g_pending.entries.clear();
            res[0] = 6;
            return ret();
        }
        g_voice_rate = mtmd_gen_audio_get_info(g_mtmd.get()).sample_rate;

        std::string content;
        for (size_t i = 0; i < g_pending.entries.size(); i++) content += mtmd_default_marker();
        content += jstr(env, jtext);
        common_chat_msg msg;
        msg.role    = "user";
        msg.content = content;
        const bool add_bos = g_n_past == 0;
        const std::string user_part = format_turn(msg, false);
        const std::string full      = format_turn(msg, true);
        const std::string gen_part  = full.substr(std::min(user_part.size(), full.size()));

        const llama_vocab * vocab = llama_model_get_vocab(g_model);
        const std::string bos_text = common_token_to_piece(vocab, llama_vocab_bos(vocab), true);
        const bool add_special = add_bos && !(bos_text.size() && user_part.rfind(bos_text, 0) == 0);
        mtmd_input_text text = { user_part.c_str(), user_part.size(), add_special, true };
        auto bitmaps = g_pending.c_ptr();
        mtmd::input_chunks chunks(mtmd_input_chunks_init());
        const int32_t tok_res = mtmd_tokenize(g_mtmd.get(), chunks.ptr.get(), &text, bitmaps.data(), bitmaps.size());
        g_pending.entries.clear();
        if (tok_res != 0) { res[0] = 1; return ret(); }
        const size_t n_tokens = mtmd_helper_get_n_tokens(chunks.ptr.get());
        res[1] = (jint) n_tokens;
        if (g_n_past + (llama_pos) n_tokens + 64 > (llama_pos) llama_n_ctx(g_ctx)) { res[0] = 4; return ret(); }

        llama_pos new_n_past = g_n_past;
        if (mtmd_helper_eval_chunks(g_mtmd.get(), g_ctx, chunks.ptr.get(), g_n_past, 0, N_BATCH, false, &new_n_past) != 0) {
            res[0] = g_abort ? 3 : 2;
            return ret();
        }
        g_n_past = new_n_past;
        g_history.push_back(msg);

        mtmd_helper_gen_audio_inp inp = mtmd_helper_gen_audio_inp_default();
        inp.seq_id     = 0;
        inp.prompt     = gen_part.c_str();
        inp.prompt_len = gen_part.size();
        inp.n_past     = g_n_past;
        inp.out_mode   = MTMD_HELPER_GEN_AUDIO_OUT_MIXED;
        inp.stream     = true;
        inp.out_type   = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;
        if (mtmd_helper_gen_audio_set_input(g_voice, &inp) != 0) { res[0] = 2; return ret(); }
        for (;;) {
            const int32_t left = mtmd_helper_gen_audio_step_prompt(g_voice, N_BATCH);
            if (left < 0) { res[0] = 2; return ret(); }
            if (left == 0) break;
        }

        common_params_sampling sparams;
        sparams.temp = temp;
        size_t n_bias = 0;
        const llama_logit_bias * bias = mtmd_helper_gen_audio_get_logit_bias(g_voice, &n_bias);
        sparams.logit_bias.assign(bias, bias + n_bias);
        if (g_smpl) common_sampler_free(g_smpl);
        g_smpl = common_sampler_init(g_model, sparams);

        g_voice_tok = common_sampler_sample(g_smpl, g_ctx, -1);
        common_sampler_accept(g_smpl, g_voice_tok, true);
        const float * h = llama_get_embeddings_ith(g_ctx, -1);
        g_voice_h.assign(h, h + llama_model_n_embd(g_model));
        g_voice_text.clear();
        g_voice_pcm.clear();
        g_utf8_cache.clear();
        g_reply.clear();
        g_voice_left   = max_steps;
        g_voice_active = true;
        guard.keep     = true;
        res[2] = g_n_past;
        LOGI("voice reply: %zu prompt tokens, speech at %d Hz", n_tokens, g_voice_rate);
    } catch (const std::exception & e) {
        backend_failed("voice prompt", e);
        res[0] = 5;
    }
    return ret();
}

// One step of the spoken reply (a text token or a speech frame). 0 = more to come, 1 = done, -1 = error.
JNIEXPORT jint JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeVoiceStep(JNIEnv *, jobject) {
    if (!g_voice_active) return 1;
    try {
        if (g_abort || g_voice_left-- <= 0 || g_n_past + 2 >= (llama_pos) llama_n_ctx(g_ctx)) {
            voice_finish();
            return 1;
        }
        const bool is_text = mtmd_helper_gen_audio_next_is_text(g_voice);
        const float * h_next = nullptr;
        bool stop = false;
        if (mtmd_helper_gen_audio_step_gen(g_voice, g_voice_tok, g_voice_h.data(), &h_next, &stop) != 0) {
            voice_finish();
            return -1;
        }
        if (is_text && !stop) {
            // control tokens (mode switches, block ends) render as nothing with special = false
            g_utf8_cache += common_token_to_piece(g_ctx, g_voice_tok, false);
            if (!utf8_incomplete(g_utf8_cache)) {
                g_voice_text += g_utf8_cache;
                g_reply      += g_utf8_cache;
                g_utf8_cache.clear();
            }
        }
        voice_collect_audio();
        if (stop || !h_next) {
            voice_finish();
            return 1;
        }
        g_voice_h.assign(h_next, h_next + llama_model_n_embd(g_model));
        g_voice_tok = common_sampler_sample(g_smpl, g_ctx, -1);
        common_sampler_accept(g_smpl, g_voice_tok, true);
        return 0;
    } catch (const std::exception & e) {
        backend_failed("voice step", e);
        voice_finish();
        return -1;
    }
}

JNIEXPORT jstring JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeVoiceText(JNIEnv * env, jobject) {
    jstring out = env->NewStringUTF(g_voice_text.c_str());
    g_voice_text.clear();
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeVoiceAudio(JNIEnv * env, jobject) {
    jfloatArray arr = env->NewFloatArray((jsize) g_voice_pcm.size());
    if (!g_voice_pcm.empty()) env->SetFloatArrayRegion(arr, 0, (jsize) g_voice_pcm.size(), g_voice_pcm.data());
    g_voice_pcm.clear();
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeVoiceRate(JNIEnv *, jobject) {
    return g_voice_rate;
}

// Close the assistant turn so the next user message is formatted after it.
JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeEndTurn(JNIEnv *, jobject) {
    // the end-of-turn token is sampled but not decoded; put it in the KV cache like the template expects
    auto eot = common_tokenize(llama_model_get_vocab(g_model), "<|im_end|>", false, true);
    if (eot.size() == 1 && g_n_past + 1 < (llama_pos) llama_n_ctx(g_ctx)) {
        g_batch.clear();
        g_batch.add(eot[0], g_n_past, 0, false);
        try {
            if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) == 0) g_n_past++;
        } catch (const std::exception & e) {
            backend_failed("end turn", e);
        }
    }
    common_chat_msg msg;
    msg.role    = "assistant";
    msg.content = g_reply;
    g_history.push_back(msg);
    g_reply.clear();
    g_utf8_cache.clear();
}

// Top NPU ops by total time since the last call, then resets. Empty when profiling is off.
JNIEXPORT jstring JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeProfileDump(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_prof_mutex);
    if (g_prof.empty()) return env->NewStringUTF("");
    std::vector<std::pair<std::string, prof_entry>> rows(g_prof.begin(), g_prof.end());
    std::sort(rows.begin(), rows.end(), [](const auto & a, const auto & b) { return a.second.usec > b.second.usec; });
    uint64_t total = 0;
    for (const auto & r : rows) total += r.second.usec;
    std::string out;
    char line[512];
    snprintf(line, sizeof(line), "NPU busy %.1f ms in %u batches (op sum %.1f ms)\n",
             g_prof_batches.usec / 1000.0, g_prof_batches.count, total / 1000.0);
    out += line;
    for (size_t i = 0; i < rows.size() && i < 20; i++) {
        const auto & r = rows[i];
        snprintf(line, sizeof(line), "%5.1f%% %8.1f ms %6u x  %.300s\n", 100.0 * r.second.usec / std::max<uint64_t>(total, 1),
                 r.second.usec / 1000.0, r.second.count, r.first.c_str());
        out += line;
    }
    g_prof.clear();
    g_prof_batches = {};
    return env->NewStringUTF(out.c_str());
}

// One-time rewrite of the model with the tied embedding/output table in Q4_0 instead of Q6_K: every generated
// token reads that 205 MB table, and the NPU's Q4_0 kernels are its fast path. Q4_0 tensors are copied as-is.
JNIEXPORT jstring JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeQuantizeHead(JNIEnv * env, jobject, jstring jin, jstring jout, jint n_threads) {
    llama_model_quantize_params qp = llama_model_quantize_default_params();
    qp.nthread              = n_threads;
    qp.ftype                = LLAMA_FTYPE_MOSTLY_Q4_0;
    qp.allow_requantize     = true;
    qp.token_embedding_type = GGML_TYPE_Q4_0;
    qp.output_tensor_type   = GGML_TYPE_Q4_0;
    uint32_t rc = 1;
    try {
        rc = llama_model_quantize(jstr(env, jin).c_str(), jstr(env, jout).c_str(), &qp);
    } catch (const std::exception & e) {
        backend_failed("quantize", e);
    }
    if (rc != 0) return env->NewStringUTF(("quantize failed " + g_last_error).c_str());
    return nullptr;
}

// Returns and clears the last backend exception message, or null.
JNIEXPORT jstring JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeLastError(JNIEnv * env, jobject) {
    if (g_last_error.empty()) return nullptr;
    jstring out = env->NewStringUTF(g_last_error.c_str());
    g_last_error.clear();
    return out;
}

JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeAbort(JNIEnv *, jobject) {
    g_abort = true;
}

JNIEXPORT jint JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeCtxUsed(JNIEnv *, jobject) {
    return g_n_past;
}

JNIEXPORT jint JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeCtxSize(JNIEnv *, jobject) {
    return g_ctx ? (jint) llama_n_ctx(g_ctx) : 0;
}


// The small second context that speech and dictation use, so the chat's own cache is never touched.
static bool side_ctx() {
    if (g_tts_ctx) return true;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = 2048;
    cp.n_batch         = N_BATCH;
    cp.n_ubatch        = N_BATCH;
    cp.n_threads       = g_threads;
    cp.n_threads_batch = g_threads;
    cp.embeddings      = false;
    cp.swa_full        = false;
    g_tts_ctx = llama_init_from_model(g_model, cp);
    if (!g_tts_ctx) return false;
    llama_set_abort_callback(g_tts_ctx, abort_cb, nullptr);
    LOGI("side context ready");
    return true;
}

// Dictation: the words in [pcm] (16 kHz mono, at most a minute) as text, using LFM3's ASR prompt
// ("Perform ASR in <language>."), in the side context. Null on failure.
JNIEXPORT jstring JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeTranscribe(JNIEnv * env, jobject, jfloatArray pcm, jstring jlang) {
    if (!g_model || !g_mtmd) return nullptr;
    g_abort = false;
    try {
        if (!side_ctx()) return nullptr;
        llama_set_embeddings(g_tts_ctx, false);
        llama_memory_clear(llama_get_memory(g_tts_ctx), true);

        const jsize n = env->GetArrayLength(pcm);
        if (n == 0) return nullptr;
        jfloat * data = env->GetFloatArrayElements(pcm, nullptr);
        mtmd::bitmap audio(mtmd_bitmap_init_from_audio(n, data));
        env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
        if (!audio.ptr) return nullptr;

        common_chat_templates_inputs inputs;
        inputs.use_jinja             = true;
        inputs.enable_thinking       = false;
        inputs.add_generation_prompt = true;
        common_chat_msg sys;
        sys.role    = "system";
        sys.content = "Perform ASR in " + jstr(env, jlang) + ".";
        common_chat_msg user;
        user.role    = "user";
        user.content = mtmd_default_marker();
        inputs.messages = { sys, user };
        const std::string prompt = common_chat_templates_apply(g_tmpls.get(), inputs).prompt.str();

        mtmd_input_text text = { prompt.c_str(), prompt.size(), false, true };
        mtmd::input_chunks chunks(mtmd_input_chunks_init());
        const mtmd_bitmap * bms[] = { audio.ptr.get() };
        if (mtmd_tokenize(g_mtmd.get(), chunks.ptr.get(), &text, bms, 1) != 0) return nullptr;
        llama_pos n_past = 0;
        if (mtmd_helper_eval_chunks(g_mtmd.get(), g_tts_ctx, chunks.ptr.get(), 0, 0, N_BATCH, true, &n_past) != 0) return nullptr;

        common_params_sampling sp;
        sp.temp       = 0;
        sp.logit_bias = text_only_bias();
        common_sampler * smpl = common_sampler_init(g_model, sp);
        const llama_vocab * vocab = llama_model_get_vocab(g_model);
        std::string out;
        // speech is at most ~3 words a second: a transcript longer than 8 tokens a second is the model rambling
        const int max_tokens = 16 + (int) (8 * n / 16000);
        for (int i = 0; i < max_tokens && n_past + 1 < (llama_pos) llama_n_ctx(g_tts_ctx) && !g_abort; i++) {
            llama_token t = common_sampler_sample(smpl, g_tts_ctx, -1);
            common_sampler_accept(smpl, t, true);
            if (llama_vocab_is_eog(vocab, t)) break;
            out += common_token_to_piece(g_tts_ctx, t, false);
            llama_batch b = llama_batch_get_one(&t, 1);
            if (llama_decode(g_tts_ctx, b) != 0) break;
            n_past++;
        }
        common_sampler_free(smpl);
        LOGI("dictation: %d samples -> %zu chars", (int) n, out.size());
        return env->NewStringUTF(out.c_str());
    } catch (const std::exception & e) {
        backend_failed("dictation", e);
        return nullptr;
    }
}

// Starts speaking [text] in TTS mode with [lang] (e.g. "en", "en-gb", "fr") and [voice] ("female" or "male").
// The audio comes out through nativeVoiceAudio as nativeSpeakStep runs. 0 = ok, 6 = the model can't speak, 2 = error.
JNIEXPORT jint JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeSpeakStart(JNIEnv * env, jobject, jstring jtext, jstring jlang, jstring jvoice) {
    if (!g_model || !g_mtmd) return 2;
    g_abort = false;
    try {
        if (mtmd_gen_audio_get_info(g_mtmd.get()).type == MTMD_GEN_AUDIO_TYPE_NONE) return 6;
        if (!side_ctx()) return 2;
        llama_set_embeddings(g_tts_ctx, true); // the speech decoder reads the hidden state
        if (!g_tts) {
            g_tts = mtmd_helper_gen_audio_init(g_tts_ctx, g_mtmd.get());
            if (!g_tts) { tts_free(); return 2; }
            LOGI("speech decoder ready");
        }
        g_voice_rate = mtmd_gen_audio_get_info(g_mtmd.get()).sample_rate;
        const std::string text  = jstr(env, jtext);
        const std::string lang  = jstr(env, jlang);
        const std::string voice = jstr(env, jvoice);
        std::string prompt = common_chat_get_tts_prompt(g_tmpls.get(), text, lang, voice);
        if (prompt.empty()) prompt = text;

        llama_memory_clear(llama_get_memory(g_tts_ctx), true);
        mtmd_helper_gen_audio_reset(g_tts);
        mtmd_helper_gen_audio_inp inp = mtmd_helper_gen_audio_inp_default();
        inp.seq_id     = 0;
        inp.prompt     = prompt.c_str();
        inp.prompt_len = prompt.size();
        inp.lang       = lang.c_str();
        inp.voice      = voice.c_str();
        inp.stream     = true;
        inp.out_type   = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;
        if (mtmd_helper_gen_audio_set_input(g_tts, &inp) != 0) return 2;
        for (;;) {
            const int32_t left = mtmd_helper_gen_audio_step_prompt(g_tts, N_BATCH);
            if (left < 0) return 2;
            if (left == 0) break;
        }
        if (g_tts_smpl) common_sampler_free(g_tts_smpl);
        common_params_sampling tts_sparams;
        g_tts_smpl = common_sampler_init(g_model, tts_sparams);
        g_tts_tok = common_sampler_sample(g_tts_smpl, g_tts_ctx, -1);
        common_sampler_accept(g_tts_smpl, g_tts_tok, true);
        const float * h = llama_get_embeddings_ith(g_tts_ctx, -1);
        g_tts_h.assign(h, h + llama_model_n_embd(g_model));
        // ~12.5 frames a second; a sentence never needs more than ~40 s
        g_tts_left   = 500;
        g_tts_active = true;
        return 0;
    } catch (const std::exception & e) {
        backend_failed("speech prompt", e);
        return 2;
    }
}

static void tts_collect() {
    int32_t rate = 0; const char * data = nullptr; size_t len = 0; int64_t n = 0;
    if (mtmd_helper_gen_audio_get_output(g_tts, &rate, &data, &len, &n) == 0 && len > 0) {
        const float * f = reinterpret_cast<const float *>(data);
        g_voice_pcm.insert(g_voice_pcm.end(), f, f + len / sizeof(float));
    }
}

// One speech frame. 0 = more, 1 = done, -1 = error.
JNIEXPORT jint JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeSpeakStep(JNIEnv *, jobject) {
    if (!g_tts_active) return 1;
    try {
        bool stop = g_abort || g_tts_left-- <= 0;
        const float * h_next = nullptr;
        if (!stop && mtmd_helper_gen_audio_step_gen(g_tts, g_tts_tok, g_tts_h.data(), &h_next, &stop) != 0) {
            g_tts_active = false;
            return -1;
        }
        if (stop || !h_next) {
            mtmd_helper_gen_audio_flush(g_tts);
            tts_collect();
            g_tts_active = false;
            return 1;
        }
        tts_collect();
        g_tts_h.assign(h_next, h_next + llama_model_n_embd(g_model));
        g_tts_tok = common_sampler_sample(g_tts_smpl, g_tts_ctx, -1);
        common_sampler_accept(g_tts_smpl, g_tts_tok, true);
        return 0;
    } catch (const std::exception & e) {
        backend_failed("speech step", e);
        g_tts_active = false;
        return -1;
    }
}

// Frees the speech context (~350 MB) when voice chat closes.
JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeSpeakFree(JNIEnv *, jobject) {
    tts_free();
}


// ---- embeddings (EmbeddingGemma) ------------------------------------------------------------------------------
// A second, small model on the CPU that turns text into a vector for meaning search. Its own model and context, so
// it can run while the chat model sits on the NPU; calls must not overlap each other (the Kotlin side serializes).

static llama_model   * g_emb_model = nullptr;
static llama_context * g_emb_ctx   = nullptr;
static mtmd_context  * g_emb_mtmd  = nullptr; // EmbeddingGemma 2's image tower, loaded for gallery search only

static jfloatArray emb_result(JNIEnv * env) {
    const float * e = llama_get_embeddings_seq(g_emb_ctx, 0);
    if (!e) return nullptr;
    const int n = llama_model_n_embd_out(g_emb_model);
    std::vector<float> out(e, e + n);
    double norm = 0;
    for (float v : out) norm += (double) v * v;
    norm = std::sqrt(norm);
    if (norm > 0) for (float & v : out) v = (float) (v / norm);
    jfloatArray arr = env->NewFloatArray(n);
    env->SetFloatArrayRegion(arr, 0, n, out.data());
    return arr;
}

// EmbeddingGemma 2's image tower on [dev] (null = CPU). The smallest image budget: ~2x faster than 280 tokens and
// plenty to tell what a photo shows.
static mtmd_context * emb_tower(const std::string & mmproj, ggml_backend_dev_t dev, int threads) {
    mtmd_context_params mp = mtmd_context_params_default();
    mp.use_gpu          = dev != nullptr;
    mp.device           = dev;
    mp.n_threads        = threads;
    mp.print_timings    = false;
    mp.warmup           = false;
    mp.flash_attn_type  = LLAMA_FLASH_ATTN_TYPE_AUTO;
    mp.image_max_tokens = 70;
    mtmd_context * m = mtmd_init_from_file(mmproj.c_str(), g_emb_model, mp);
    LOGI("embedding image tower %s on %s", m ? "loaded" : "failed to load", dev ? ggml_backend_dev_name(dev) : "CPU");
    return m;
}

// [mmproj] may be empty: text only. Loading again with an mmproj adds the image tower, on [jenc] (a device name such
// as "GPUOpenCL") or the CPU when empty or not found.
JNIEXPORT jboolean JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeEmbedLoad(JNIEnv * env, jobject, jstring jpath, jstring jmmproj, jint threads, jstring jenc) {
    const std::string mmproj = jstr(env, jmmproj);
    const std::string enc    = jstr(env, jenc);
    try {
        if (!g_emb_ctx) {
            ggml_backend_dev_t cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
            ggml_backend_dev_t devs[] = { cpu, nullptr };
            auto mp = llama_model_default_params();
            mp.devices      = devs;
            mp.n_gpu_layers = 0;
            g_emb_model = llama_model_load_from_file(jstr(env, jpath).c_str(), mp);
            if (!g_emb_model) return JNI_FALSE;
            auto cp = llama_context_default_params();
            cp.n_ctx           = 2048;
            cp.n_batch         = 2048;
            cp.n_ubatch        = 2048; // non-causal: a text must fit one ubatch
            cp.embeddings      = true;
            cp.n_threads       = threads;
            cp.n_threads_batch = threads;
            g_emb_ctx = llama_init_from_model(g_emb_model, cp);
            if (!g_emb_ctx) { llama_model_free(g_emb_model); g_emb_model = nullptr; return JNI_FALSE; }
            LOGI("embedding model loaded: %d dims, pooling %d", llama_model_n_embd_out(g_emb_model), (int) llama_pooling_type(g_emb_ctx));
        }
        if (mmproj.empty()) return JNI_TRUE;
        if (!g_emb_mtmd) {
            ggml_backend_dev_t dev = enc.empty() ? nullptr : find_device(enc);
            if (!enc.empty() && !dev) { LOGI("embedding image tower: no %s device", enc.c_str()); return JNI_FALSE; }
            g_emb_mtmd = emb_tower(mmproj, dev, threads);
        }
        return g_emb_mtmd ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception & e) {
        backend_failed("embedding model load", e);
        return JNI_FALSE;
    }
}

// Frees only the image tower (to load it again on another device).
JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeEmbedDropTower(JNIEnv *, jobject) {
    if (g_emb_mtmd) mtmd_free(g_emb_mtmd);
    g_emb_mtmd = nullptr;
}

JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeEmbedUnload(JNIEnv *, jobject) {
    if (g_emb_mtmd) mtmd_free(g_emb_mtmd);
    if (g_emb_ctx) llama_free(g_emb_ctx);
    if (g_emb_model) llama_model_free(g_emb_model);
    g_emb_mtmd = nullptr;
    g_emb_ctx = nullptr;
    g_emb_model = nullptr;
}

// The unit-length embedding of [text] (cut to the model's 2048 tokens), or null on failure.
JNIEXPORT jfloatArray JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeEmbed(JNIEnv * env, jobject, jstring jtext) {
    if (!g_emb_ctx) return nullptr;
    try {
        const llama_vocab * vocab = llama_model_get_vocab(g_emb_model);
        std::vector<llama_token> toks = common_tokenize(vocab, jstr(env, jtext), true, true);
        const int max_toks = (int) llama_n_ubatch(g_emb_ctx);
        if ((int) toks.size() > max_toks) toks.resize(max_toks);
        if (toks.empty()) return nullptr;
        llama_memory_clear(llama_get_memory(g_emb_ctx), true);
        llama_batch batch = llama_batch_init((int) toks.size(), 0, 1);
        for (size_t i = 0; i < toks.size(); i++) {
            batch.token[i]     = toks[i];
            batch.pos[i]       = (llama_pos) i;
            batch.n_seq_id[i]  = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i]    = true;
        }
        batch.n_tokens = (int32_t) toks.size();
        const int rc = llama_decode(g_emb_ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) return nullptr;
        return emb_result(env);
    } catch (const std::exception & e) {
        backend_failed("embedding", e);
        return nullptr;
    }
}

// The unit-length embedding of a photo (RGB bytes), in the same space as text queries; null on failure.
// The context pools per call, so only the image's own tokens are evaluated, not the text around its marker.
JNIEXPORT jfloatArray JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeEmbedImage(JNIEnv * env, jobject, jbyteArray rgb, jint w, jint h) {
    if (!g_emb_ctx || !g_emb_mtmd) return nullptr;
    try {
        const jsize n = env->GetArrayLength(rgb);
        if (n != w * h * 3) return nullptr;
        std::vector<unsigned char> px((size_t) n);
        env->GetByteArrayRegion(rgb, 0, n, reinterpret_cast<jbyte *>(px.data()));
        mtmd::bitmap bm(w, h, px.data());
        std::string marker = mtmd_default_marker();
        mtmd_input_text text = { marker.c_str(), marker.size(), true, true };
        mtmd::input_chunks chunks(mtmd_input_chunks_init());
        const mtmd_bitmap * bms[] = { bm.ptr.get() };
        if (mtmd_tokenize(g_emb_mtmd, chunks.ptr.get(), &text, bms, 1) != 0) return nullptr;
        llama_memory_clear(llama_get_memory(g_emb_ctx), true);
        bool done = false;
        for (size_t i = 0; i < chunks.size(); i++) {
            const mtmd_input_chunk * c = chunks[i];
            if (mtmd_input_chunk_get_type(c) != MTMD_INPUT_CHUNK_TYPE_IMAGE) continue;
            llama_pos np = 0;
            if (mtmd_helper_eval_chunk_single(g_emb_mtmd, g_emb_ctx, c, 0, 0, (int32_t) llama_n_ubatch(g_emb_ctx), true, &np) != 0) return nullptr;
            done = true;
            break;
        }
        return done ? emb_result(env) : nullptr;
    } catch (const std::exception & e) {
        backend_failed("image embedding", e);
        return nullptr;
    }
}

// ---- OCR: MiniCPM-OCR 1.0, a separate small model that writes down the text in a picture -----------------------
// Loaded only while reading. Its text part runs on the CPU (the NPU's memory is taken by the chat model); the image
// tower runs on [enc_dev] when given (the Adreno GPU), else the CPU.

static llama_model *              g_ocr_model = nullptr;
static llama_context *            g_ocr_ctx   = nullptr;
static mtmd_context *             g_ocr_mtmd  = nullptr;
static common_chat_templates_ptr  g_ocr_tmpls;
static std::atomic<bool>          g_ocr_abort{false};

static void ocr_free() {
    if (g_ocr_mtmd) mtmd_free(g_ocr_mtmd);
    if (g_ocr_ctx) llama_free(g_ocr_ctx);
    if (g_ocr_model) llama_model_free(g_ocr_model);
    g_ocr_tmpls.reset();
    g_ocr_mtmd = nullptr;
    g_ocr_ctx = nullptr;
    g_ocr_model = nullptr;
}

// Returns where the image tower runs ("GPUOpenCL", "CPU"), or null when loading failed.
JNIEXPORT jstring JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeOcrLoad(JNIEnv * env, jobject, jstring jpath, jstring jmmproj, jstring jenc, jint threads) {
    if (g_ocr_ctx && g_ocr_mtmd) return env->NewStringUTF("loaded");
    ocr_free();
    try {
        ggml_backend_dev_t cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        ggml_backend_dev_t devs[] = { cpu, nullptr };
        auto mp = llama_model_default_params();
        mp.devices      = devs;
        mp.n_gpu_layers = 0;
        g_ocr_model = llama_model_load_from_file(jstr(env, jpath).c_str(), mp);
        if (!g_ocr_model) return nullptr;
        auto cp = llama_context_default_params();
        cp.n_ctx           = 8192; // up to 10 slices of 256 image tokens, plus a page of text
        cp.n_batch         = 512;
        cp.n_ubatch        = 512;
        cp.n_threads       = threads;
        cp.n_threads_batch = threads;
        cp.abort_callback      = [](void *) { return g_ocr_abort.load(); };
        g_ocr_ctx = llama_init_from_model(g_ocr_model, cp);
        if (!g_ocr_ctx) { ocr_free(); return nullptr; }
        g_ocr_tmpls = common_chat_templates_init(g_ocr_model, "");

        const std::string enc = jstr(env, jenc);
        ggml_backend_dev_t enc_dev = enc.empty() ? nullptr : find_device(enc);
        mtmd_context_params vp = mtmd_context_params_default();
        vp.use_gpu         = enc_dev != nullptr;
        vp.device          = enc_dev;
        vp.n_threads       = threads;
        vp.print_timings   = true;
        vp.warmup          = false;
        vp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        g_ocr_mtmd = mtmd_init_from_file(jstr(env, jmmproj).c_str(), g_ocr_model, vp);
        if (!g_ocr_mtmd) { ocr_free(); return nullptr; }
        const char * where = enc_dev ? ggml_backend_dev_name(enc_dev) : "CPU";
        LOGI("OCR model loaded, image tower on %s", where);
        return env->NewStringUTF(where);
    } catch (const std::exception & e) {
        backend_failed("OCR load", e);
        ocr_free();
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeOcrUnload(JNIEnv *, jobject) { ocr_free(); }

// One-time: the downloaded image tower is bf16, which the phone's backends run slowly; write a Q8_0 copy.
JNIEXPORT jboolean JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeMmprojToQ8(JNIEnv * env, jobject, jstring jsrc, jstring jdst) {
    return mmproj_to_q8(jstr(env, jsrc), jstr(env, jdst)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeOcrStop(JNIEnv *, jobject, jboolean stop) { g_ocr_abort = stop; }

// The text in a picture (RGB bytes) as the model writes it down, or null on failure. [prompt] is the task,
// "Text Recognition:" for pages and photos.
JNIEXPORT jstring JNICALL
Java_com_saad_omnichat_llm_LlamaNative_nativeOcrRead(JNIEnv * env, jobject, jbyteArray rgb, jint w, jint h, jstring jprompt) {
    if (!g_ocr_ctx || !g_ocr_mtmd || g_ocr_abort) return nullptr;
    try {
        const jsize n = env->GetArrayLength(rgb);
        if (n != w * h * 3) return nullptr;
        std::vector<unsigned char> px((size_t) n);
        env->GetByteArrayRegion(rgb, 0, n, reinterpret_cast<jbyte *>(px.data()));
        mtmd::bitmap bm(w, h, px.data());

        common_chat_templates_inputs inputs;
        inputs.use_jinja             = true;
        inputs.enable_thinking       = false;
        inputs.add_generation_prompt = true;
        common_chat_msg user;
        user.role    = "user";
        user.content = std::string(mtmd_default_marker()) + jstr(env, jprompt);
        inputs.messages = { user };
        const std::string prompt = common_chat_templates_apply(g_ocr_tmpls.get(), inputs).prompt.str();

        const int64_t t0 = ggml_time_ms();
        mtmd_input_text text = { prompt.c_str(), prompt.size(), true, true };
        mtmd::input_chunks chunks(mtmd_input_chunks_init());
        const mtmd_bitmap * bms[] = { bm.ptr.get() };
        if (mtmd_tokenize(g_ocr_mtmd, chunks.ptr.get(), &text, bms, 1) != 0) return nullptr;
        llama_memory_clear(llama_get_memory(g_ocr_ctx), true);
        llama_pos n_past = 0;
        if (mtmd_helper_eval_chunks(g_ocr_mtmd, g_ocr_ctx, chunks.ptr.get(), 0, 0, 512, true, &n_past) != 0) return nullptr;
        const int64_t t1 = ggml_time_ms();

        common_params_sampling sp;
        sp.temp = 0;
        common_sampler * smpl = common_sampler_init(g_ocr_model, sp);
        const llama_vocab * vocab = llama_model_get_vocab(g_ocr_model);
        std::string out;
        int n_gen = 0;
        while (n_past + 1 < (llama_pos) llama_n_ctx(g_ocr_ctx) && !g_ocr_abort) {
            llama_token t = common_sampler_sample(smpl, g_ocr_ctx, -1);
            common_sampler_accept(smpl, t, true);
            if (llama_vocab_is_eog(vocab, t)) break;
            out += common_token_to_piece(g_ocr_ctx, t, false);
            n_gen++;
            // a greedy reader can get stuck repeating a line: stop when the last 60 chars are the 4th copy in a row
            if (out.size() > 240 && n_gen % 16 == 0) {
                const std::string tail = out.substr(out.size() - 60);
                size_t reps = 0;
                for (size_t at = out.size(); at >= 60 && out.compare(at - 60, 60, tail) == 0; at -= 60) reps++;
                if (reps >= 4) break;
            }
            llama_batch b = llama_batch_get_one(&t, 1);
            if (llama_decode(g_ocr_ctx, b) != 0) break;
            n_past++;
        }
        common_sampler_free(smpl);
        LOGI("OCR: %dx%d, read in %lld ms, wrote %d tokens in %lld ms", (int) w, (int) h, (long long) (t1 - t0), n_gen, (long long) (ggml_time_ms() - t1));
        return env->NewStringUTF(out.c_str());
    } catch (const std::exception & e) {
        backend_failed("OCR", e);
        return nullptr;
    }
}

} // extern "C"


