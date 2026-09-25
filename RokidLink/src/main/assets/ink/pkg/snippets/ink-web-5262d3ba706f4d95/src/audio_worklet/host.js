let assetRoot;
let nextId = 1;
const contexts = new Map();
export function configureAudioAssets(url) {
  assetRoot = url;
}
const requireContext = (id) => {
  const entry = contexts.get(id);
  if (!entry || entry.closed) throw new DOMException('AudioContext is closed', 'InvalidStateError');
  if (entry.error) throw entry.error;
  return entry;
};
export function createAudioContext(rate) {
  if (!assetRoot)
    throw new DOMException('Web Audio assets have not been configured', 'NotSupportedError');
  const context = new AudioContext(rate ? { sampleRate: rate } : {});
  if (!context.audioWorklet) {
    context.close();
    throw new DOMException('AudioWorklet is unavailable', 'NotSupportedError');
  }
  const id = nextId++;
  const entry = {
    context,
    pending: [],
    histories: new Map(),
    events: [],
    frame: 0,
    running: false,
    closed: false,
    initialized: false,
    mediaSources: new Map(),
  };
  contexts.set(id, entry);
  entry.ready = (async () => {
    await context.suspend();
    const response = await fetch(new URL('ink_audio_worklet_processor_bg.wasm', assetRoot));
    if (!response.ok) throw new Error(`Audio Wasm download failed: ${response.status}`);
    const module = await WebAssembly.compile(await response.arrayBuffer());
    await context.audioWorklet.addModule(new URL('worklet.js', assetRoot));
    if (entry.closed) return;
    const node = new AudioWorkletNode(context, 'ink-audio-graph', {
      numberOfInputs: 16,
      numberOfOutputs: 1,
      outputChannelCount: [2],
      processorOptions: { module },
    });
    entry.node = node;
    await new Promise((resolve, reject) => {
      entry.cancelReady = () =>
        reject(new DOMException('AudioContext is closed', 'InvalidStateError'));
      node.onprocessorerror = () => {
        entry.error = new Error('Audio renderer failed');
        reject(entry.error);
      };
      node.port.onmessage = ({ data }) => {
        if (data.ready) resolve();
        if (data.error) {
          entry.error = new Error(data.error);
          reject(entry.error);
        }
        if (data.frame !== undefined) entry.frame = data.frame;
        if (data.events) {
          entry.events.push(...data.events);
          for (const event of data.events) {
            if (event.Retired)
              entry.histories.delete(`${event.Retired.index}:${event.Retired.generation}`);
          }
        }
        if (data.history)
          entry.histories.set(`${data.index}:${data.generation}`, {
            frame: data.frame,
            data: data.history,
          });
      };
    });
    entry.cancelReady = undefined;
    if (entry.closed) throw new DOMException('AudioContext is closed', 'InvalidStateError');
    node.connect(context.destination);
    for (const [message, transfers] of entry.pending) node.port.postMessage(message, transfers);
    entry.pending.length = 0;
    entry.initialized = true;
  })();
  entry.ready.catch((error) => {
    entry.error = error;
  });
  return id;
}
export function audioMediaStreamSource(id, index, generation, stream) {
  const entry = requireContext(id);
  const key = `${index}:${generation}`;
  if (entry.mediaSources.has(key)) return;
  const used = new Set(Array.from(entry.mediaSources.values(), (value) => value.slot));
  let slot = -1;
  for (let candidate = 0; candidate < 16; candidate += 1) {
    if (!used.has(candidate)) {
      slot = candidate;
      break;
    }
  }
  if (slot < 0) throw new DOMException('MediaStream input capacity exceeded', 'NotSupportedError');
  const source = entry.context.createMediaStreamSource(stream);
  entry.mediaSources.set(key, { source, slot });
  entry.ready
    .then(() => {
      if (entry.closed || !entry.mediaSources.has(key)) return;
      source.connect(entry.node, 0, slot);
      entry.node.port.postMessage({ mediaSource: { index, generation, slot } });
    })
    .catch(() => {});
}
export function audioRemoveMediaStreamSource(id, index, generation) {
  const entry = contexts.get(id);
  const key = `${index}:${generation}`;
  const record = entry?.mediaSources.get(key);
  if (!record) return;
  record.source.disconnect();
  entry.node?.port.postMessage({ removeMediaSource: record.slot });
  entry.mediaSources.delete(key);
}
function post(entry, message, transfers = []) {
  if (entry.initialized) entry.node.port.postMessage(message, transfers);
  else {
    if (entry.pending.length >= 1024)
      throw new DOMException('Audio command queue is full', 'NotSupportedError');
    entry.pending.push([message, transfers]);
  }
}
export function audioSampleRate(id) {
  return contexts.get(id)?.context.sampleRate ?? 48000;
}
export function audioCurrentTime(id) {
  const entry = contexts.get(id);
  return entry ? entry.frame / entry.context.sampleRate : 0;
}
export function audioState(id) {
  const entry = contexts.get(id);
  return !entry || entry.closed
    ? 'closed'
    : !entry.error && entry.running && entry.context.state === 'running'
      ? 'running'
      : 'suspended';
}
export function audioCommand(id, json) {
  post(requireContext(id), { command: json });
}
export function audioBuffer(id, index, generation, rate, channels, data) {
  post(requireContext(id), { buffer: data, index, generation, rate, channels }, [data.buffer]);
}
export async function audioRunning(id, value) {
  const entry = requireContext(id);
  // Start resume in the user-activation call stack, before awaiting readiness.
  const resume = value ? entry.context.resume() : entry.context.suspend();
  await entry.ready;
  await resume;
  if (entry.closed) throw new DOMException('AudioContext is closed', 'InvalidStateError');
  entry.running = value;
  post(entry, { running: value });
}
export function audioClose(id) {
  const entry = contexts.get(id);
  if (!entry || entry.closed) return;
  entry.closed = true;
  entry.running = false;
  entry.cancelReady?.();
  for (const record of entry.mediaSources.values()) record.source.disconnect();
  entry.mediaSources.clear();
  if (entry.node) {
    entry.node.port.postMessage({ close: true });
    entry.node.disconnect();
    entry.node.port.close();
  }
  entry.pending.length = 0;
  entry.histories.clear();
  entry.context.close().catch(() => {});
  contexts.delete(id);
}
export function audioPoll(id) {
  const entry = contexts.get(id);
  if (!entry) return '[]';
  if (entry.node) entry.node.port.postMessage({ collect: true });
  return JSON.stringify(entry.events.splice(0));
}
export function audioHistory(id, index, generation) {
  const entry = requireContext(id);
  post(entry, { history: true, index, generation });
  return (
    entry.histories.get(`${index}:${generation}`) ?? { frame: 0, data: new Float32Array(32768) }
  );
}
export async function audioDecode(id, data) {
  const entry = requireContext(id);
  const buffer = await entry.context.decodeAudioData(data.buffer);
  return {
    sampleRate: buffer.sampleRate,
    channels: Array.from({ length: buffer.numberOfChannels }, (_, i) => buffer.getChannelData(i)),
  };
}
