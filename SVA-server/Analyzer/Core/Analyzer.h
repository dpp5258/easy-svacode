#ifndef ANALYZER_ANALYZER_H
#define ANALYZER_ANALYZER_H

#include <string>
#include <vector>
#include <cstdint>
#include <functional>
#include <unordered_map>
#include <opencv2/opencv.hpp>
#include <iostream>
#include <filesystem>
#include "BehaviorEvaluator.h"
namespace SVAAnalyzer
{
	struct Control;
	struct AlgorithmTask;
	class Config;
	class Scheduler;
	class Algorithm;
	struct DetectObject;

	class Analyzer
	{
	public:
		explicit Analyzer(Scheduler *scheduler, Control *control);
		~Analyzer();

	public:
		bool handleVideoFrame(int64_t frameCount, cv::Mat &image, std::vector<DetectObject> &happenDetects, bool &happen, float &happenScore, bool isKeyframe = false);

	private:
		bool runAlgorithmTask(int64_t frameCount,
						 AlgorithmTask &task,
						 cv::Mat &image,
						 std::vector<DetectObject> &taskDetects,
						 bool &taskHappen,
						 float &taskHappenScore,
						 bool isKeyframe);
		Algorithm *resolveAlgorithm(const std::string &algorithmCode);
		void applyRegionAndObjectMatch(const AlgorithmTask &task,
								 std::vector<DetectObject> &detects,
								 bool &happen,
								 float &happenScore);

	private:
		bool postImage2Server(int64_t frameCount, cv::Mat &image, std::vector<DetectObject> &happenDetects, bool &happen, float &happenScore);

	private:
		Scheduler *mScheduler;
		Control *mControl;

		// ---- [ROI 放大 + 三档] 按框退避(追踪在 Worker 才打 trackId, 故此处用量化框位置做键) ----
		struct RoiBackoffKey
		{
			int qx = 0;
			int qy = 0;
			int qh = 0;
			bool operator==(const RoiBackoffKey &o) const { return qx == o.qx && qy == o.qy && qh == o.qh; }
		};
		struct RoiBackoffKeyHash
		{
			size_t operator()(const RoiBackoffKey &k) const
			{
				return static_cast<size_t>(k.qx) ^ (static_cast<size_t>(k.qy) << 1) ^ (static_cast<size_t>(k.qh) << 2);
			}
		};
		struct RoiBackoff
		{
			int fail = 0;
			int64_t lastMs = 0;
		};
		std::unordered_map<RoiBackoffKey, RoiBackoff, RoiBackoffKeyHash> mRoiBackoff;
		uint64_t mRoiAttempt = 0;
		uint64_t mRoiOk = 0;
		int64_t mRoiLogMs = 0;
	private:
	};
}
#endif // ANALYZER_ANALYZER_H
