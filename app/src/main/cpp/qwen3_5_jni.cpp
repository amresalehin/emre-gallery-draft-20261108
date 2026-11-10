#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {
constexpr const char * kTag = "EmreQwen35";
constexpr int32_t kContextSize = 4096;
constexpr int32_t kBatchSize = 1024;
constexpr int32_t kThreads = 4;

std::mutex g_mutex;
bool g_backend_initialized = false;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
mtmd_context * g_vision = nullptr;
std::string g_last_error;

void set_error(const std::string & message) {
    g_last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

void release_model_locked() {
    if (g_vision) {
        mtmd_free(g_vision);
        g_vision = nullptr;
    }
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(1024);
    int32_t count = llama_token_to_piece(vocab, token, buffer.data(),
                                         static_cast<int32_t>(buffer.size()), 0, false);
    if (count < 0) {
        buffer.resize(static_cast<size_t>(-count));
        count = llama_token_to_piece(vocab, token, buffer.data(),
                                     static_cast<int32_t>(buffer.size()), 0, false);
    }
    if (count <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(count));
}

bool decode_generated_token(llama_context * context, llama_batch & batch,
                            llama_token token, llama_pos position) {
    batch.n_tokens = 1;
    batch.token[0] = token;
    batch.pos[0] = position;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0] = 1;
    return llama_decode(context, batch) == 0;
}

std::string build_chat_prompt(const std::string & system_prompt) {
    const std::string media_marker = mtmd_default_marker();
    const llama_chat_message messages[] = {
        { "system", system_prompt.c_str() },
        { "user", media_marker.c_str() },
    };

    const char * chat_template = llama_model_chat_template(g_model, nullptr);
    if (chat_template) {
        const size_t requested = system_prompt.size() + media_marker.size() + 2048U;
        if (requested <= static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
            std::vector<char> buffer(requested);
            int32_t length = llama_chat_apply_template(chat_template, messages, 2, true,
                                                       buffer.data(), static_cast<int32_t>(buffer.size()));
            if (length >= 0) {
                if (static_cast<size_t>(length) >= buffer.size()) {
                    buffer.resize(static_cast<size_t>(length) + 1U);
                    length = llama_chat_apply_template(chat_template, messages, 2, true,
                                                       buffer.data(), static_cast<int32_t>(buffer.size()));
                }
                if (length >= 0 && static_cast<size_t>(length) < buffer.size()) {
                    return std::string(buffer.data(), static_cast<size_t>(length));
                }
            }
        }
    }

    // Qwen3.5 uses the ChatML role structure. This is a safe fallback for GGUFs
    // that do not expose a chat template understood by llama_chat_apply_template().
    return "<|im_start|>system\n" + system_prompt + "<|im_end|>\n" +
           "<|im_start|>user\n" + media_marker + "<|im_end|>\n" +
           "<|im_start|>assistant\n";
}

jstring make_java_string(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_amresalehin_emregallery_ai_local_Qwen35Native_initialize(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }
    g_last_error.clear();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_amresalehin_emregallery_ai_local_Qwen35Native_loadModel(
        JNIEnv * env, jobject /*thiz*/, jstring j_model_path, jstring j_projector_path) {
    if (!j_model_path || !j_projector_path) return JNI_FALSE;
    const char * model_path = env->GetStringUTFChars(j_model_path, nullptr);
    const char * projector_path = env->GetStringUTFChars(j_projector_path, nullptr);
    if (!model_path || !projector_path) {
        if (model_path) env->ReleaseStringUTFChars(j_model_path, model_path);
        if (projector_path) env->ReleaseStringUTFChars(j_projector_path, projector_path);
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    release_model_locked();
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(j_model_path, model_path);
    if (!g_model) {
        env->ReleaseStringUTFChars(j_projector_path, projector_path);
        set_error("llama.cpp could not load the Qwen3.5-0.8B language model GGUF.");
        return JNI_FALSE;
    }

    mtmd_context_params vision_params = mtmd_context_params_default();
    vision_params.use_gpu = false;
    vision_params.print_timings = false;
    vision_params.n_threads = kThreads;
    vision_params.warmup = false;
    g_vision = mtmd_init_from_file(projector_path, g_model, vision_params);
    env->ReleaseStringUTFChars(j_projector_path, projector_path);

    if (!g_vision || !mtmd_support_vision(g_vision)) {
        release_model_locked();
        set_error("The Qwen3.5 vision projector could not be initialized or does not expose vision input.");
        return JNI_FALSE;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = kContextSize;
    context_params.n_batch = kBatchSize;
    context_params.n_ubatch = std::min<int32_t>(512, kBatchSize);
    context_params.n_threads = kThreads;
    context_params.n_threads_batch = kThreads;
    context_params.offload_kqv = false;
    g_context = llama_init_from_model(g_model, context_params);
    if (!g_context) {
        release_model_locked();
        set_error("llama.cpp could not create the Qwen3.5 inference context.");
        return JNI_FALSE;
    }

    g_last_error.clear();
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "Qwen3.5 language model and F16 vision projector loaded");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_amresalehin_emregallery_ai_local_Qwen35Native_generateImage(
        JNIEnv * env, jobject /*thiz*/, jbyteArray j_image, jstring j_system_prompt, jint max_tokens) {
    if (!j_image || !j_system_prompt) return nullptr;
    const jsize image_size = env->GetArrayLength(j_image);
    if (image_size <= 0) return nullptr;

    jbyte * image_data = env->GetByteArrayElements(j_image, nullptr);
    const char * system_prompt_chars = env->GetStringUTFChars(j_system_prompt, nullptr);
    if (!image_data || !system_prompt_chars) {
        if (image_data) env->ReleaseByteArrayElements(j_image, image_data, JNI_ABORT);
        if (system_prompt_chars) env->ReleaseStringUTFChars(j_system_prompt, system_prompt_chars);
        return nullptr;
    }
    const std::string system_prompt(system_prompt_chars);
    env->ReleaseStringUTFChars(j_system_prompt, system_prompt_chars);

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_context || !g_vision) {
        env->ReleaseByteArrayElements(j_image, image_data, JNI_ABORT);
        set_error("Qwen3.5-0.8B is not loaded.");
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(g_context), true);
    mtmd_bitmap * bitmap = mtmd_helper_bitmap_init_from_buf(
        g_vision, reinterpret_cast<const unsigned char *>(image_data), static_cast<size_t>(image_size));
    env->ReleaseByteArrayElements(j_image, image_data, JNI_ABORT);
    if (!bitmap) {
        set_error("libmtmd could not decode the supplied image bytes.");
        return nullptr;
    }

    const std::string prompt = build_chat_prompt(system_prompt);
    mtmd_input_text input_text = { prompt.c_str(), false, true };
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    if (!chunks) {
        mtmd_bitmap_free(bitmap);
        set_error("libmtmd could not allocate image input chunks.");
        return nullptr;
    }
    const mtmd_bitmap * bitmaps[] = { bitmap };
    const int32_t tokenize_result = mtmd_tokenize(g_vision, chunks, &input_text, bitmaps, 1);
    mtmd_bitmap_free(bitmap);
    if (tokenize_result != 0) {
        mtmd_input_chunks_free(chunks);
        set_error("libmtmd could not tokenize the Qwen3.5 image prompt (code " +
                  std::to_string(tokenize_result) + ").");
        return nullptr;
    }

    llama_pos next_position = 0;
    const int32_t eval_result = mtmd_helper_eval_chunks(
        g_vision, g_context, chunks, 0, 0, kBatchSize, true, &next_position);
    mtmd_input_chunks_free(chunks);
    if (eval_result != 0) {
        set_error("libmtmd image/prompt evaluation failed (code " +
                  std::to_string(eval_result) + ").");
        return nullptr;
    }

    llama_sampler * sampler = llama_sampler_init_greedy();
    if (!sampler) {
        set_error("llama.cpp could not create the greedy sampler.");
        return nullptr;
    }
    llama_batch batch = llama_batch_init(1, 0, 1);
    if (!batch.token || !batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits) {
        llama_batch_free(batch);
        llama_sampler_free(sampler);
        set_error("llama.cpp could not allocate a generation batch.");
        return nullptr;
    }

    std::string generated;
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int token_limit = std::clamp<int>(max_tokens, 1, 256);
    for (int i = 0; i < token_limit; ++i) {
        const llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        generated += token_piece(vocab, token);
        llama_sampler_accept(sampler, token);
        if (!decode_generated_token(g_context, batch, token, next_position++)) {
            set_error("llama.cpp failed while decoding a Qwen3.5 output token.");
            generated.clear();
            break;
        }
    }

    llama_batch_free(batch);
    llama_sampler_free(sampler);
    if (generated.empty()) {
        if (g_last_error.empty()) set_error("Qwen3.5 generated an empty response.");
        return nullptr;
    }
    g_last_error.clear();
    return make_java_string(env, generated);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_amresalehin_emregallery_ai_local_Qwen35Native_lastError(
        JNIEnv * env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return make_java_string(env, g_last_error);
}

extern "C" JNIEXPORT void JNICALL
Java_com_amresalehin_emregallery_ai_local_Qwen35Native_close(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_model_locked();
    g_last_error.clear();
}
