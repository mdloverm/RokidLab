import './worklet-encoding.js';
import { initSync, AudioProcessor, audio_memory } from './ink_audio_worklet_processor.js';

class InkAudioGraph extends AudioWorkletProcessor {
  constructor(options) {
    super();
    initSync({ module: options.processorOptions.module });
    this.engine = new AudioProcessor(sampleRate);
    this.memory = audio_memory();
    this.inputMemory = new Float32Array(this.memory.buffer, this.engine.input_memory(), 4096);
    this.mediaInputs = new Map();
    this.running = false;
    this.offset = 128;
    this.port.onmessage = ({ data }) => {
      try {
        if (data.close) {
          this.engine.free();
          this.engine = null;
          return;
        }
        if (!this.engine) return;
        if (data.mediaSource) this.mediaInputs.set(data.mediaSource.slot, data.mediaSource);
        if (data.removeMediaSource !== undefined) this.mediaInputs.delete(data.removeMediaSource);
        if (data.command) this.engine.command(data.command);
        if (data.buffer)
          this.engine.buffer(data.index, data.generation, data.rate, data.channels, data.buffer);
        if (data.running !== undefined) this.running = data.running;
        if (data.history) {
          const pointer = this.engine.history(data.index, data.generation);
          const history = new Float32Array(this.memory.buffer, pointer, 32768).slice();
          this.port.postMessage(
            { history, index: data.index, generation: data.generation, frame: this.engine.frame() },
            [history.buffer],
          );
        }
        if (data.collect) {
          this.engine.collect();
          if (this.engine.has_events())
            this.port.postMessage({ events: JSON.parse(this.engine.events()) });
        }
      } catch (error) {
        this.port.postMessage({ error: String(error) });
      }
    };
    this.port.postMessage({ ready: true });
  }
  process(inputs, outputs) {
    if (!this.engine) return false;
    if (!this.running) return true;
    if (this.block && this.block.buffer !== this.memory.buffer)
      this.block = new Float32Array(this.memory.buffer, this.pointer, 256);
    const output = outputs[0];
    for (let i = 0; i < output[0].length; i++) {
      if (this.offset === 128) {
        if (this.inputMemory.buffer !== this.memory.buffer) {
          this.inputMemory = new Float32Array(this.memory.buffer, this.engine.input_memory(), 4096);
        }
        for (const [slot, source] of this.mediaInputs) {
          const input = inputs[slot];
          if (!input || input.length === 0) continue;
          for (let channel = 0; channel < input.length; channel++) {
            this.inputMemory.set(input[channel], channel * 128);
          }
          this.engine.media_input(source.index, source.generation, input.length);
        }
        const pointer = this.engine.render();
        this.pointer = pointer;
        this.block = new Float32Array(this.memory.buffer, pointer, 256);
        this.offset = 0;
        if (this.engine.has_events())
          this.port.postMessage({ events: JSON.parse(this.engine.events()) });
      }
      for (let channel = 0; channel < output.length; channel++)
        output[channel][i] = this.block[channel * 128 + this.offset];
      this.offset++;
    }
    this.port.postMessage({ frame: this.engine.frame() });
    return true;
  }
}
registerProcessor('ink-audio-graph', InkAudioGraph);
