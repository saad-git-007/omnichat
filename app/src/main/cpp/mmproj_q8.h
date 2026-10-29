// Rewrites a bf16 image tower (mmproj) with its big matrices in Q8_0 and the rest of the bf16 ones in f16: half the
// size, and types the CPU, OpenCL and Hexagon backends all have fast kernels for (bf16 mostly falls back to slow
// paths). Streams one tensor at a time, so memory stays at the size of the largest tensor.
#pragma once
#include "ggml.h"
#include "gguf.h"
#include <cstdio>
#include <string>
#include <vector>

static bool mmproj_to_q8(const std::string & src_path, const std::string & dst_path) {
    ggml_context * meta = nullptr;
    gguf_init_params ip = { /*no_alloc =*/ true, /*ctx =*/ &meta };
    gguf_context * src = gguf_init_from_file(src_path.c_str(), ip);
    if (!src) return false;
    gguf_context * dst = gguf_init_empty();
    gguf_set_kv(dst, src);
    const int64_t n = gguf_get_n_tensors(src);
    std::vector<ggml_type> to(n);
    for (int64_t i = 0; i < n; i++) {
        const char * name = gguf_get_tensor_name(src, i);
        ggml_tensor * t = ggml_get_tensor(meta, name);
        gguf_add_tensor(dst, t);
        to[i] = t->type;
        if (t->type == GGML_TYPE_BF16) {
            to[i] = (ggml_n_dims(t) == 2 && t->ne[0] % ggml_blck_size(GGML_TYPE_Q8_0) == 0) ? GGML_TYPE_Q8_0 : GGML_TYPE_F16;
            gguf_set_tensor_type(dst, name, to[i]);
        }
    }
    FILE * in = fopen(src_path.c_str(), "rb");
    FILE * out = fopen(dst_path.c_str(), "wb");
    bool ok = in && out && gguf_write_to_file_ptr(dst, out, /*only_meta =*/ true);
    const size_t meta_size = gguf_get_meta_size(dst);
    const size_t src_data = gguf_get_data_offset(src);
    std::vector<uint8_t> buf;
    std::vector<float> f32;
    std::vector<uint8_t> conv;
    for (int64_t i = 0; ok && i < n; i++) {
        ggml_tensor * t = ggml_get_tensor(meta, gguf_get_tensor_name(src, i));
        const size_t size = gguf_get_tensor_size(src, i);
        buf.resize(size);
        ok = fseek(in, (long) (src_data + gguf_get_tensor_offset(src, i)), SEEK_SET) == 0 && fread(buf.data(), 1, size, in) == size;
        if (!ok) break;
        const uint8_t * data = buf.data();
        size_t out_size = size;
        if (to[i] != t->type) {
            const int64_t ne = ggml_nelements(t);
            f32.resize(ne);
            ggml_bf16_to_fp32_row(reinterpret_cast<const ggml_bf16_t *>(buf.data()), f32.data(), ne);
            if (to[i] == GGML_TYPE_F16) {
                conv.resize(ne * sizeof(ggml_fp16_t));
                ggml_fp32_to_fp16_row(f32.data(), reinterpret_cast<ggml_fp16_t *>(conv.data()), ne);
                out_size = conv.size();
            } else {
                const int64_t rows = ne / t->ne[0];
                conv.resize(ggml_row_size(to[i], t->ne[0]) * rows);
                out_size = ggml_quantize_chunk(to[i], f32.data(), conv.data(), 0, rows, t->ne[0], nullptr);
            }
            data = conv.data();
        }
        ok = fseek(out, (long) (meta_size + gguf_get_tensor_offset(dst, i)), SEEK_SET) == 0 && fwrite(data, 1, out_size, out) == out_size;
    }
    // the data section ends padded to the alignment
    if (ok && n > 0) {
        const size_t end = meta_size + gguf_get_tensor_offset(dst, n - 1) + gguf_get_tensor_size(dst, n - 1);
        const size_t align = gguf_get_alignment(dst);
        const size_t padded = (end + align - 1) / align * align;
        if (padded > end) { std::vector<uint8_t> z(padded - end, 0); ok = fseek(out, (long) end, SEEK_SET) == 0 && fwrite(z.data(), 1, z.size(), out) == z.size(); }
    }
    if (in) fclose(in);
    if (out && fclose(out) != 0) ok = false;
    gguf_free(dst);
    gguf_free(src);
    ggml_free(meta);
    if (!ok) remove(dst_path.c_str());
    return ok;
}
