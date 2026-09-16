//
// Created by fujiayi on 2020/7/1.
//

#include "ocr_ppredictor.h"
#include "common.h"
#include "ocr_cls_process.h"
#include "ocr_crnn_process.h"
#include "ocr_db_post_process.h"
#include "preprocess.h"

namespace ppredictor {

OCR_PPredictor::OCR_PPredictor(const OCR_Config &config) : _config(config) {}

int OCR_PPredictor::init(const std::string &det_model_content,
                         const std::string &rec_model_content,
                         const std::string &cls_model_content) {
  // 三个子模型任一创建失败都必须向上报错：原实现忽略各 init_nb 的返回值、
  // 无条件返回 RETURN_OK，半残的 predictor 会被当成可用，此后 OCR 恒返回空
  _det_predictor = std::unique_ptr<PPredictor>(
      new PPredictor{_config.thread_num, NET_OCR, _config.mode});
  if (_det_predictor->init_nb(det_model_content) != RETURN_OK) {
    LOGE("det model init failed");
    return RETURN_ERROR;
  }

  _rec_predictor = std::unique_ptr<PPredictor>(
      new PPredictor{_config.thread_num, NET_OCR_INTERNAL, _config.mode});
  if (_rec_predictor->init_nb(rec_model_content) != RETURN_OK) {
    LOGE("rec model init failed");
    return RETURN_ERROR;
  }

  _cls_predictor = std::unique_ptr<PPredictor>(
      new PPredictor{_config.thread_num, NET_OCR_INTERNAL, _config.mode});
  if (_cls_predictor->init_nb(cls_model_content) != RETURN_OK) {
    LOGE("cls model init failed");
    return RETURN_ERROR;
  }
  return RETURN_OK;
}

int OCR_PPredictor::init_from_file(const std::string &det_model_path,
                                   const std::string &rec_model_path,
                                   const std::string &cls_model_path) {
  // 同 init()：逐个校验，任一失败即向上报错
  _det_predictor = std::unique_ptr<PPredictor>(
      new PPredictor{_config.thread_num, NET_OCR, _config.mode});
  if (_det_predictor->init_from_file(det_model_path) != RETURN_OK) {
    LOGE("det model init failed: %s", det_model_path.c_str());
    return RETURN_ERROR;
  }

  _rec_predictor = std::unique_ptr<PPredictor>(
      new PPredictor{_config.thread_num, NET_OCR_INTERNAL, _config.mode});
  if (_rec_predictor->init_from_file(rec_model_path) != RETURN_OK) {
    LOGE("rec model init failed: %s", rec_model_path.c_str());
    return RETURN_ERROR;
  }

  _cls_predictor = std::unique_ptr<PPredictor>(
      new PPredictor{_config.thread_num, NET_OCR_INTERNAL, _config.mode});
  if (_cls_predictor->init_from_file(cls_model_path) != RETURN_OK) {
    LOGE("cls model init failed: %s", cls_model_path.c_str());
    return RETURN_ERROR;
  }
  return RETURN_OK;
}
/**
 * for debug use, show result of First Step
 * @param filter_boxes
 * @param boxes
 * @param srcimg
 */
static void
visual_img(const std::vector<std::vector<std::vector<int>>> &filter_boxes,
           const std::vector<std::vector<std::vector<int>>> &boxes,
           const cv::Mat &srcimg) {
  // visualization
  cv::Point rook_points[filter_boxes.size()][4];
  for (int n = 0; n < filter_boxes.size(); n++) {
    for (int m = 0; m < filter_boxes[0].size(); m++) {
      rook_points[n][m] =
          cv::Point(int(filter_boxes[n][m][0]), int(filter_boxes[n][m][1]));
    }
  }

  cv::Mat img_vis;
  srcimg.copyTo(img_vis);
  for (int n = 0; n < boxes.size(); n++) {
    const cv::Point *ppt[1] = {rook_points[n]};
    int npt[] = {4};
    cv::polylines(img_vis, ppt, npt, 1, 1, CV_RGB(0, 255, 0), 2, 8, 0);
  }
  // 调试用，自行替换需要修改的路径
  cv::imwrite("/sdcard/1/vis.png", img_vis);
}

std::vector<OCRPredictResult>
OCR_PPredictor::infer_ocr(const std::vector<int64_t> &dims,
                          const float *input_data, int input_len, int net_flag,
                          cv::Mat &origin) {
  PredictorInput input = _det_predictor->get_first_input();
  input.set_dims(dims);
  input.set_data(input_data, input_len);
  std::vector<PredictorOutput> results = _det_predictor->infer();
  PredictorOutput &res = results.at(0);
  std::vector<std::vector<std::vector<int>>> filtered_box = calc_filtered_boxes(
      res.get_float_data(), res.get_size(), (int)dims[2], (int)dims[3], origin);
  LOGI("Filter_box size %ld", filtered_box.size());
  return infer_rec(filtered_box, origin);
}

std::vector<OCRPredictResult> OCR_PPredictor::infer_rec(
    const std::vector<std::vector<std::vector<int>>> &boxes,
    const cv::Mat &origin_img) {
  std::vector<float> mean = {0.5f, 0.5f, 0.5f};
  std::vector<float> scale = {1 / 0.5f, 1 / 0.5f, 1 / 0.5f};
  std::vector<int64_t> dims = {1, 3, 0, 0};
  std::vector<OCRPredictResult> ocr_results;

  PredictorInput input = _rec_predictor->get_first_input();
  for (auto bp = boxes.crbegin(); bp != boxes.crend(); ++bp) {
    const std::vector<std::vector<int>> &box = *bp;
    cv::Mat crop_img = get_rotate_crop_image(origin_img, box);
    crop_img = infer_cls(crop_img);

    float wh_ratio = float(crop_img.cols) / float(crop_img.rows);
    cv::Mat input_image = crnn_resize_img(crop_img, wh_ratio);
    input_image.convertTo(input_image, CV_32FC3, 1 / 255.0f);
    const float *dimg = reinterpret_cast<const float *>(input_image.data);
    int input_size = input_image.rows * input_image.cols;

    dims[2] = input_image.rows;
    dims[3] = input_image.cols;
    input.set_dims(dims);

    neon_mean_scale(dimg, input.get_mutable_float_data(), input_size, mean,
                    scale);

    std::vector<PredictorOutput> results = _rec_predictor->infer();
    const float *predict_batch = results.at(0).get_float_data();
    const std::vector<int64_t> predict_shape = results.at(0).get_shape();

    OCRPredictResult res;

    // ctc decode
    int argmax_idx;
    int last_index = 0;
    float score = 0.f;
    int count = 0;
    float max_value = 0.0f;

    for (int n = 0; n < predict_shape[1]; n++) {
      argmax_idx = int(argmax(&predict_batch[n * predict_shape[2]],
                              &predict_batch[(n + 1) * predict_shape[2]]));
      max_value =
          float(*std::max_element(&predict_batch[n * predict_shape[2]],
                                  &predict_batch[(n + 1) * predict_shape[2]]));
      if (argmax_idx > 0 && (!(n > 0 && argmax_idx == last_index))) {
        score += max_value;
        count += 1;
        res.word_index.push_back(argmax_idx);
      }
      last_index = argmax_idx;
    }
    score /= count;
    if (res.word_index.empty()) {
      continue;
    }
    res.score = score;
    res.points = box;
    ocr_results.emplace_back(std::move(res));
  }
  LOGI("ocr_results finished %lu", ocr_results.size());
  return ocr_results;
}

cv::Mat OCR_PPredictor::infer_cls(const cv::Mat &img, float thresh) {
  std::vector<float> mean = {0.5f, 0.5f, 0.5f};
  std::vector<float> scale = {1 / 0.5f, 1 / 0.5f, 1 / 0.5f};
  std::vector<int64_t> dims = {1, 3, 0, 0};
  std::vector<OCRPredictResult> ocr_results;

  PredictorInput input = _cls_predictor->get_first_input();

  cv::Mat input_image = cls_resize_img(img);
  input_image.convertTo(input_image, CV_32FC3, 1 / 255.0f);
  const float *dimg = reinterpret_cast<const float *>(input_image.data);
  int input_size = input_image.rows * input_image.cols;

  dims[2] = input_image.rows;
  dims[3] = input_image.cols;
  input.set_dims(dims);

  neon_mean_scale(dimg, input.get_mutable_float_data(), input_size, mean,
                  scale);

  std::vector<PredictorOutput> results = _cls_predictor->infer();

  const float *scores = results.at(0).get_float_data();
  float score = 0;
  int label = 0;
  for (int64_t i = 0; i < results.at(0).get_size(); i++) {
    LOGI("output scores [%f]", scores[i]);
    if (scores[i] > score) {
      score = scores[i];
      label = i;
    }
  }
  cv::Mat srcimg;
  img.copyTo(srcimg);
  if (label % 2 == 1 && score > thresh) {
    cv::rotate(srcimg, srcimg, 1);
  }
  return srcimg;
}

std::vector<std::vector<std::vector<int>>>
OCR_PPredictor::calc_filtered_boxes(const float *pred, int pred_size,
                                    int output_height, int output_width,
                                    const cv::Mat &origin) {
  const double threshold = 0.3;
  const double maxvalue = 1;

  cv::Mat pred_map = cv::Mat::zeros(output_height, output_width, CV_32F);
  // 这里原本是 memcpy(pred_map.data, pred, pred_size * sizeof(float))，
  // 隐含假设「det 模型输出元素数 == 输入图宽高之积」。该假设在输入图过小时不成立：
  // det 模型内部有下采样与最小特征图约束，输入太小时输出反而多于按输入算出的 pred_map 容量，
  // memcpy 遂写穿 cv::Mat 的堆缓冲区，破坏堆元数据，随后在任意一次分配/清零时崩溃
  // （典型栈顶为 libc 的 __memset_aarch64，因 cv::Mat::zeros 内部用 memset 清零）。
  // 这正是「传入图片小于约 360x260 就会崩溃」的根因——每次小图 OCR 都在破坏堆，
  // 只是是否立刻崩取决于越界写恰好损坏了哪块内存。
  // 此处按实际容量截断，任何情况下都不越界；尺寸不符时记日志便于定位调用方。
  size_t capacity = (size_t)output_height * (size_t)output_width;
  size_t copy_num = (size_t)pred_size < capacity ? (size_t)pred_size : capacity;
  if ((size_t)pred_size != capacity) {
    LOGE("det output size mismatch: pred_size=%d capacity=%zu (%dx%d), copy %zu",
         pred_size, capacity, output_width, output_height, copy_num);
  }
  memcpy(pred_map.data, pred, copy_num * sizeof(float));
  cv::Mat cbuf_map;
  pred_map.convertTo(cbuf_map, CV_8UC1);

  cv::Mat bit_map;
  cv::threshold(cbuf_map, bit_map, threshold, maxvalue, cv::THRESH_BINARY);

  std::vector<std::vector<std::vector<int>>> boxes =
      boxes_from_bitmap(pred_map, bit_map);
  float ratio_h = output_height * 1.0f / origin.rows;
  float ratio_w = output_width * 1.0f / origin.cols;
  std::vector<std::vector<std::vector<int>>> filter_boxes =
      filter_tag_det_res(boxes, ratio_h, ratio_w, origin);
  return filter_boxes;
}

std::vector<int>
OCR_PPredictor::postprocess_rec_word_index(const PredictorOutput &res) {
  const int *rec_idx = res.get_int_data();
  const std::vector<std::vector<uint64_t>> rec_idx_lod = res.get_lod();

  std::vector<int> pred_idx;
  for (int n = int(rec_idx_lod[0][0]); n < int(rec_idx_lod[0][1] * 2); n += 2) {
    pred_idx.emplace_back(rec_idx[n]);
  }
  return pred_idx;
}

float OCR_PPredictor::postprocess_rec_score(const PredictorOutput &res) {
  const float *predict_batch = res.get_float_data();
  const std::vector<int64_t> predict_shape = res.get_shape();
  const std::vector<std::vector<uint64_t>> predict_lod = res.get_lod();
  int blank = predict_shape[1];
  float score = 0.f;
  int count = 0;
  for (int n = predict_lod[0][0]; n < predict_lod[0][1] - 1; n++) {
    int argmax_idx = argmax(predict_batch + n * predict_shape[1],
                            predict_batch + (n + 1) * predict_shape[1]);
    float max_value = predict_batch[n * predict_shape[1] + argmax_idx];
    if (blank - 1 - argmax_idx > 1e-5) {
      score += max_value;
      count += 1;
    }
  }
  if (count == 0) {
    LOGE("calc score count 0");
  } else {
    score /= count;
  }
  LOGI("calc score: %f", score);
  return score;
}

NET_TYPE OCR_PPredictor::get_net_flag() const { return NET_OCR; }
}