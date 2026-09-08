#ifndef ANALYZER_ALGORITHMONYOLO_H
#define ANALYZER_ALGORITHMONYOLO_H

#include <string>
#include <vector>
#include <mutex>
#include <queue>
#include "Algorithm.h"
#include <onnxruntime_cxx_api.h>

namespace SVAAnalyzer
{
	class Config;
	enum class YoloOutputDecoder
	{
		DenseWithNms,
		DirectDetections,
		Pose,
	};

	/**
	 * @brief ONNX Runtime inference engine.
	 * 
	 * Teaching note: This is the inference backend for object detection.
	 * GPU build priority: TensorRT > CUDA > CPU (auto-fallback).
	 * CPU-only build: compile with -DSVA_ONNXRUNTIME_GPU=OFF and link CPU-only ONNX Runtime.
	 * 
	 * - TensorRT: NVIDIA's inference optimizer, requires .engine cache build on first run, fastest.
	 * - CUDA: Direct CUDA execution, no engine build needed, faster than CPU.
	 * - CPU: Fallback when no GPU is available, uses optimized CPU kernels.
	 * 
	 * Configuration via config.json: pipelineGpuStrict=true skips GPU if not available.
	 */
	class OnnxRuntimeEngine
	{
	public:
		explicit OnnxRuntimeEngine(Config *config, std::string &modelPath, std::vector<std::string> &classNames, const std::string &algorithmCode);
		~OnnxRuntimeEngine();

	public:
		bool runInference(cv::Mat &image, std::vector<DetectObject> &detects);
		bool isGpuEnabled() const { return mGpuEnabled; }
		std::string getActiveProvider() const { return mActiveProvider; }
		/**
		 * @brief ROI 放大二次推理(算法规则.md §8.5.1): 以 target 为中心裁 pad*max(w,h) 正方形窗,
		 *        letterbox 640 推理 → decode → 坐标平移回原图 → 按与 target 的 IoU 位置匹配(禁 maxconf)。
		 * @return true 且 out 为匹配到的检测(原图坐标, hd/poseOk/keypoints 齐备)
		 */
		bool runPoseRoi(cv::Mat &fullImage, const DetectObject &target,
						float pad, float matchIoU, DetectObject &out);

	private:
		Config *mConfig;
		std::vector<std::string> mClassNames;
		std::string mModelPath;
		std::string mAlgorithmCode;
		YoloOutputDecoder mDecoder = YoloOutputDecoder::DenseWithNms;
		std::string mInputNodeName;
		std::string mOutputNodeName;
		int mInputWidth = 0;
		int mInputHeight = 0;
		int mOutputDim = 0;
		int mOutputRow = 0;
		std::vector<int64_t> mOutputDims;
		bool mGpuEnabled = false;
		std::string mActiveProvider = "CPU";
		Ort::Env mEnv{nullptr};
		Ort::SessionOptions mSessionOptions{nullptr};
		Ort::Session mSession{nullptr};

		void initPostprocessProfile(const std::string &algorithmCode);
		bool decodeDenseOutputWithNms(const float *pdata, int imageWidth, int imageHeight, int paddedImageSize, std::vector<DetectObject> &detects);
		bool decodeDirectDetections(const float *pdata, int imageWidth, int imageHeight, std::vector<DetectObject> &detects);
		// (1,56,8400): 4 box + 1 conf + 17*3 kpts(x,y,conf); letterbox gray-114 input (scale/pad offsets in 640 space)
		bool decodePoseOutput(const float *pdata, int imageWidth, int imageHeight,
							  float scale, int padX, int padY, std::vector<DetectObject> &detects);	};
	class AlgorithmOnYolo : public Algorithm
	{
	public:
		AlgorithmOnYolo(Config *config, std::string &modelPath, std::vector<std::string> &classNames, const std::string &algorithmCode);
		virtual ~AlgorithmOnYolo();

	public:
		virtual bool objectDetect(cv::Mat &image, std::vector<DetectObject> &detects);
		/** ROI 升级门面: 成功则把 ROI 检出的 hd/poseOk 写回 det(不改 box/keypoints, 保持追踪语义), 置 poseFromRoi=true */
		bool roiUpgradePose(cv::Mat &image, DetectObject &det, float pad, float matchIoU);

	private:
		std::vector<std::string> mClassNames;
		OnnxRuntimeEngine *mEngine;
	};
}
#endif // ANALYZER_ALGORITHMONYOLO_H
