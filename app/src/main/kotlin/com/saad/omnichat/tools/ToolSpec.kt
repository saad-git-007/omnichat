package com.saad.omnichat.tools

/** A tool offered to the model: rendered into the system turn by LFM3's chat template. */
data class ToolSpec(val name: String, val description: String, val parameters: String)

/** A call the model made: `<tool_call><function=name><parameter=k>v</parameter>…</function></tool_call>`. */
data class ToolCall(val name: String, val args: Map<String, String>)

object ToolCalls {
  private val fn = Regex("""<function=([\w.-]+)>([\s\S]*?)(?:</function>|$)""")
  private val param = Regex("""<parameter=([\w.-]+)>\s*([\s\S]*?)\s*</parameter>""")

  /** Null unless the call is complete: a reply cut off by the token limit mid-call has nothing reliable to run. */
  fun parse(raw: String): ToolCall? {
    val call = raw.substringAfter("<tool_call>", "").ifEmpty { return null }
    if ("</function>" !in call && "</tool_call>" !in call) return null
    val m = fn.find(call) ?: return null
    return ToolCall(m.groupValues[1], param.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].trim() })
  }

  /** JSON-schema object with string properties; [required] names the mandatory ones. */
  fun params(vararg props: Pair<String, String>, required: List<String> = emptyList(), types: Map<String, String> = emptyMap()): String {
    val o = org.json.JSONObject()
    props.forEach { (k, d) -> o.put(k, org.json.JSONObject().put("type", types[k] ?: "string").put("description", d)) }
    return org.json.JSONObject().put("type", "object").put("properties", o).put("required", org.json.JSONArray(required)).toString()
  }
}
