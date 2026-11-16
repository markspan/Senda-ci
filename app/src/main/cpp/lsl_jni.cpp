// Minimal JNI bridge to the liblsl C API: just what Sensorium needs to publish outlets.
#include <jni.h>
#include <android/log.h>
#include <lsl_c.h>

#include <string>
#include <vector>

#define LOG_TAG "sensoriumlsl"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define JNI_FN(name) Java_io_github_markspan_sensorium_lsl_LslNative_##name

namespace {

std::string toString(JNIEnv *env, jstring s) {
	if (s == nullptr) return {};
	const char *chars = env->GetStringUTFChars(s, nullptr);
	std::string out(chars ? chars : "");
	if (chars) env->ReleaseStringUTFChars(s, chars);
	return out;
}

std::string elementAt(JNIEnv *env, jobjectArray arr, jsize i) {
	if (arr == nullptr || i >= env->GetArrayLength(arr)) return {};
	auto js = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
	std::string out = toString(env, js);
	env->DeleteLocalRef(js);
	return out;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_FN(libraryVersion)(JNIEnv *, jobject) {
	return lsl_library_version();
}

JNIEXPORT jstring JNICALL JNI_FN(libraryInfo)(JNIEnv *env, jobject) {
	return env->NewStringUTF(lsl_library_info());
}

JNIEXPORT jdouble JNICALL JNI_FN(localClock)(JNIEnv *, jobject) {
	return lsl_local_clock();
}

/**
 * Creates a float32 outlet. Each channel gets <label>, <unit> and <type> meta-data; the channel
 * label is additionally written as <name> for compatibility with the PolarBLE desktop streams.
 * `channelUnits` holds one unit per channel, or a single unit that applies to all channels.
 * Returns the outlet handle, or 0 on failure.
 */
JNIEXPORT jlong JNICALL JNI_FN(createOutlet)(JNIEnv *env, jobject, jstring name, jstring type,
	jint channelCount, jdouble srate, jstring sourceId, jobjectArray channelLabels,
	jobjectArray channelUnits, jstring channelType, jstring manufacturer, jstring model,
	jint chunkSize, jint maxBuffered, jstring timestamps) {
	const std::string sName = toString(env, name), sType = toString(env, type),
					  sSource = toString(env, sourceId), sChType = toString(env, channelType),
					  sManuf = toString(env, manufacturer), sModel = toString(env, model),
					  sStamps = toString(env, timestamps);

	lsl_streaminfo info = lsl_create_streaminfo(
		sName.c_str(), sType.c_str(), channelCount, srate, cft_float32, sSource.c_str());
	if (!info) {
		LOGE("lsl_create_streaminfo failed for %s", sName.c_str());
		return 0;
	}

	lsl_xml_ptr desc = lsl_get_desc(info);
	if (!sManuf.empty()) lsl_append_child_value(desc, "manufacturer", sManuf.c_str());
	if (!sModel.empty()) lsl_append_child_value(desc, "model", sModel.c_str());
	lsl_xml_ptr channels = lsl_append_child(desc, "channels");
	const jsize nUnits = channelUnits ? env->GetArrayLength(channelUnits) : 0;
	for (jsize i = 0; i < channelCount; ++i) {
		std::string label = elementAt(env, channelLabels, i);
		if (label.empty()) label = "ch" + std::to_string(i + 1);
		const std::string unit = elementAt(env, channelUnits, nUnits == 1 ? 0 : i);
		lsl_xml_ptr ch = lsl_append_child(channels, "channel");
		lsl_append_child_value(ch, "label", label.c_str());
		lsl_append_child_value(ch, "name", label.c_str());
		if (!unit.empty()) lsl_append_child_value(ch, "unit", unit.c_str());
		if (!sChType.empty()) lsl_append_child_value(ch, "type", sChType.c_str());
	}
	lsl_xml_ptr acq = lsl_append_child(desc, "acquisition");
	lsl_append_child_value(acq, "software", "Sensorium (Android)");
	// How the samples are time stamped, so an analysis can tell the two modes apart.
	if (!sStamps.empty()) lsl_append_child_value(acq, "timestamps", sStamps.c_str());

	lsl_outlet outlet = lsl_create_outlet(info, chunkSize, maxBuffered);
	lsl_destroy_streaminfo(info); // the outlet keeps its own copy
	if (!outlet) LOGE("lsl_create_outlet failed for %s", sName.c_str());
	return reinterpret_cast<jlong>(outlet);
}

/**
 * Pushes a chunk of multiplexed samples. The most recent (last) sample is stamped with timestamp
 * (lsl_local_clock() domain), or with lsl_local_clock() at push time when timestamp is 0; liblsl
 * spaces the earlier samples back by the nominal rate.
 */
JNIEXPORT jint JNICALL JNI_FN(pushChunk)(
	JNIEnv *env, jobject, jlong handle, jfloatArray data, jdouble timestamp) {
	auto outlet = reinterpret_cast<lsl_outlet>(handle);
	const jsize nData = env->GetArrayLength(data);
	if (!outlet || nData == 0) return 0;

	std::vector<float> buf(static_cast<size_t>(nData));
	env->GetFloatArrayRegion(data, 0, nData, buf.data());
	return lsl_push_chunk_ftp(outlet, buf.data(), static_cast<unsigned long>(nData), timestamp, 1);
}

/** Pushes one sample with an explicit time stamp in the lsl_local_clock() domain. */
JNIEXPORT jint JNICALL JNI_FN(pushSample)(
	JNIEnv *env, jobject, jlong handle, jfloatArray data, jdouble timestamp) {
	auto outlet = reinterpret_cast<lsl_outlet>(handle);
	const jsize nData = env->GetArrayLength(data);
	if (!outlet || nData == 0) return 0;

	float buf[64];
	std::vector<float> big;
	float *p = buf;
	if (nData > 64) {
		big.resize(static_cast<size_t>(nData));
		p = big.data();
	}
	env->GetFloatArrayRegion(data, 0, nData, p);
	return lsl_push_sample_ftp(outlet, p, timestamp, 1);
}

JNIEXPORT jboolean JNICALL JNI_FN(haveConsumers)(JNIEnv *, jobject, jlong handle) {
	auto outlet = reinterpret_cast<lsl_outlet>(handle);
	return outlet && lsl_have_consumers(outlet) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(destroyOutlet)(JNIEnv *, jobject, jlong handle) {
	auto outlet = reinterpret_cast<lsl_outlet>(handle);
	if (outlet) lsl_destroy_outlet(outlet);
}

} // extern "C"
