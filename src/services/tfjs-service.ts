/**
 * @license
 * Copyright 2019 Google LLC.
 * This code may only be used under the BSD style license found at
 * http://polymer.github.io/LICENSE.txt
 * Code distributed by Google as part of this project is also
 * subject to an additional IP rights grant found at
 * http://polymer.github.io/PATENTS.txt
 */
import {Reference, ResourceManager as rmgr} from './resource-manager.js';
import {logFactory} from '../platform/log-web.js';
import {Service, Services} from '../runtime/services.js';
import {requireTf} from '../platform/tf-web.js';
import {loadImage} from '../platform/image-web.js';
import {tf} from '../platform/tf-node.js';

const log = logFactory('tfjs-service');

type TfTensor = tf.Tensor | tf.Tensor[] | tf.NamedTensorMap;

export interface ClassificationPrediction {
  className: string;
  probability: number;
}

abstract class TfModel implements Service {

  public abstract async load({modelUrl, options}): Promise<Reference>;

  /**
   * Execute inference for the input tensors.
   *
   * @param {Reference} model An inference model
   * @param {Reference} inputs The input tensor
   * @param {tf.ModelPredictConfig} config Control verbosity and batchSize.
   */
  public async predict({model, inputs, config}): Promise<Reference> {
    const tf = await requireTf();

    log('Referencing model and input...');
    const model_  = rmgr.deref(model) as tf.InferenceModel;
    const inputs_ = rmgr.deref(inputs) as TfTensor;

    log('Predicting...');
    const yHat = await model_.predict(inputs_, config) as TfTensor;

    log('Predicted.');
    return rmgr.ref(yHat);
  }

  /**
   * Load the model weights eagerly, so subsequent calls to `predict` will be fast.
   *
   * @param {Reference} model An inference model
   * @see https://www.tensorflow.org/js/guide/platform_environment#shader_compilation_texture_uploads
   */
  public async warmUp({model}): Promise<void> {
    const tf = await requireTf();

    log('Warming up model...');
    const model_  = rmgr.deref(model) as tf.InferenceModel;

    const zeros = model_.inputs
      .map(i => i.shape ? i.shape : [])
      .map((sh) => sh.map(x => Math.abs(x)))
      .map((sh) => tf.zeros(sh || [1]));

    const zeroInput = zeros.length === 1 ? zeros[0] : zeros;

    const result = await model_.predict(zeroInput, {}) as tf.Tensor;
    result.dispose();

    log('Model warm.');
  }

  /** Clean up resources */
  public dispose({reference}): void {
    rmgr.dispose(reference);
  }
}


class GraphModel extends TfModel {
  /**
   * Load a graph model given a URL to the model definition.
   *
   * @param {Reference} modelUrl the url that loads the model.
   * @param {tf.io.LoadOptions} options Options for the HTTP request, which allows to send credentials
   *  and custom headers.
   * @return {Reference} A reference to the in-memory model.
   */
  public async load({modelUrl, options}): Promise<Reference> {
    const tf = await requireTf();

    log('Loading model...');
    const model = await tf.loadGraphModel(modelUrl, options);

    log('Model loaded.');
    return rmgr.ref(model);
  }

  /**
   * Properly clean up the resource used for the graph model.
   *
   * @param {Reference} reference to a graph model.
   */
  public dispose({reference}): void {
    const model_ = rmgr.deref(reference) as tf.GraphModel;
    model_.dispose();
    super.dispose(reference);
  }

}

class LayersModel extends TfModel {
  /**
   * Load a layers model given a URL to the model definition.
   *
   * @param {Reference} modelUrl the url that loads the model.
   * @param {tf.io.LoadOptions} options Options for the HTTP request, which allows to send credentials
   *  and custom headers.
   * @return {Reference} A reference to the in-memory model.
   */
  async load({modelUrl, options}): Promise<Reference> {
    const tf = await requireTf();

    log('Loading model...');
    const model = await tf.loadLayersModel(modelUrl, options);

    log('Model loaded.');
    return rmgr.ref(model);
  }
}

/**
 * Converts a URL of an image into a 3D tensor.
 *
 * @param {string} imageUrl image to convert
 * @return {Reference} The tf.Tensor3D representation of the image.
 * @see {tf.browser.fromPixels()}
 */
const imageToTensor = async ({imageUrl}): Promise<Reference> => {
  const tf = await requireTf();

  log('Converting image to tensor...');
  const imgElem = await loadImage(imageUrl);
  const imgTensor = await tf.browser.fromPixels(imgElem, 3) as tf.Tensor3D;

  log('Image converted.');
  return rmgr.ref(imgTensor);
};


/**
 * Given the range of possible values, ensure all elements fall between -1 and 1.
 *
 * @param {Reference} input Tensor to normalize.
 * @param {[number, number]} range [hi, low] tuple. Default: [0, 255].
 * @return {Reference} A new tensor with values normalized.
 */
const normalize = async ({input, range=[0, 255]}): Promise<Reference> => {
  const tf = await requireTf();

  log('Normalizing...');
  const input_ = rmgr.deref(input) as tf.Tensor;

  const max_ = Math.max(...range);
  const min_ = Math.min(...range);
  const mid = (max_ - min_) / 2 + min_;

  const normOffset = tf.scalar(mid);

  const normalized = input_.toFloat()
    .sub(normOffset)
    .div(normOffset) as tf.Tensor3D;

  log('Normalized.');
  return rmgr.ref(normalized);
};

/**
 * Transform the shape of the input tensor into a new shape, preserving the number of elements.
 *
 * @param {Reference} input The tensor to transform.
 * @param {number[]} newShape Desired shape. Must specify `newShape` or `shape`.
 * @param {number[]} shape Desired shape. Must specify `newShape` or `shape`.
 * @return {Reference} The reshaped tensor.
 */
const reshape = async ({input, newShape, shape}): Promise<Reference> => {
  const tf = await requireTf();

  log('Reshaping...');
  const input_ = rmgr.deref(input);
  const resized = await tf.reshape(input_, newShape || shape);

  log('Reshaped.');
  return rmgr.ref(resized);
};

/**
 * Expand (increase) the number of dimensions of a tensor by one.
 *
 * @param {Reference} input Tensor to transform. Must specify `input` or `x`.
 * @param {Reference} x Tensor to transform. Must specify `input` or `x`.
 * @param {number} axis Integer value, dimension to expand on. Default: 0
 * @return {Reference} A tensor with expanded rank.
 */
const expandDims = async ({input, x, axis = 0}): Promise<Reference> => {
  const tf = await requireTf();

  log('Expanding dimensions...');
  const input_ = rmgr.deref(input || x);
  const expanded = tf.expandDims(input_, axis);

  log('Dimensions expanded.');
  return rmgr.ref(expanded);
};

/**
 * Use bilinear-interpolation to resize a batch of images.
 *
 * @param {Reference} images A batch of images to resize of rank 4 or 3. If rank 3, a batch of 1 is assumed.
 * @param {[number, number]} size The new shape `[newHeight, newWidth]` to resize the image to.
 * @param {boolean} alignCorners  Defaults to False. If true, rescale input by (new_height - 1) / (height - 1),
 *  which exactly aligns the 4 corners of images and resized images. If false, rescale by new_height / height.
 *  Treat similarly the width dimension. Optional
 * @return {Reference} Images with a new shape.
 */
const resizeBilinear = async ({images, size, alignCorners}): Promise<Reference> => {
  const tf = await requireTf();

  log('Bilinear resizing image(s)...');
  const images_ = rmgr.deref(images);
  const resized = await tf.image.resizeBilinear(images_, size, alignCorners);
  log('Resized image(s).');

  return rmgr.ref(resized);
};

/**
 * Return a ranked list (of size K) of classes with their associated probabilities.
 *
 * @param {Reference} input Prediction tensor. Must specify either `input`, `y`, or `yHat`.
 * @param {Reference} y Prediction tensor. Must specify either `input`, `y`, or `yHat`.
 * @param {Reference} yHat Prediction tensor. Must specify either `input`, `y`, or `yHat`.
 * @param {string[]} labels A mapping between label index and value.
 * @param {number} topK The total number of labels to return
 * @return {ClassificationPrediction[]} A list of predictions, complete with `className` and `probability`.
 */
const getTopKClasses = async ({input, y, yHat, labels, topK=3}): Promise<ClassificationPrediction[]> => {
  log('Getting top K classes...');
  const input_ = rmgr.deref(input || y || yHat) as tf.Tensor2D;

  const softmax = input_.softmax();
  const values = await softmax.data();
  softmax.dispose();

  const valuesAndIndices = [];
  for (let i = 0; i < values.length; i++) {
    valuesAndIndices.push({value: values[i], index: i});
  }
  valuesAndIndices.sort((a, b) => {
    return b.value - a.value;
  });
  const topkValues = new Float32Array(topK);
  const topkIndices = new Int32Array(topK);
  for (let i = 0; i < topK; i++) {
    topkValues[i] = valuesAndIndices[i].value;
    topkIndices[i] = valuesAndIndices[i].index;
  }

  const topClassesAndProbs = [];
  for (let i = 0; i < topkIndices.length; i++) {
    topClassesAndProbs.push({
      className: labels[topkIndices[i]],
      probability: topkValues[i]
    });
  }
  log('found top K classes.');
  return topClassesAndProbs;
};


Services.register('graph-model', new GraphModel());
Services.register('layer-model', new LayersModel());

Services.register('preprocess', {
  normalize,
  reshape,
  expandDims,
});

Services.register('postprocess', {
  getTopKClasses,
});

Services.register('tf-image', {
  resizeBilinear,
  imageToTensor,
});


