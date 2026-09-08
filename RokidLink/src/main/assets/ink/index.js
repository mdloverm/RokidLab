import initInkWasm, {
  InkWebView as RawInkWebView,
  get_bundle_version as getInkBundleVersionFromWasm,
  get_version as getInkVersionFromWasm,
  satisfies_engine as satisfiesInkEngineFromWasm,
} from './pkg/ink_web.js';
import {
  clearInkRequestInterceptor as clearInkRequestInterceptorBridge,
  fetchWithInkRequestInterceptor,
  setInkRequestInterceptor as setInkRequestInterceptorBridge,
} from './env.js';

let bindingsPromise;
let configuredNavigatorHost = null;

function getDefaultScaleFactor() {
  return Number(globalThis.devicePixelRatio || 1);
}

function normalizeScaleFactor(value) {
  const numericValue = Number(value);
  if (Number.isFinite(numericValue) && numericValue > 0) {
    return numericValue;
  }

  const defaultScaleFactor = getDefaultScaleFactor();
  if (Number.isFinite(defaultScaleFactor) && defaultScaleFactor > 0) {
    return defaultScaleFactor;
  }

  return 1;
}

function normalizeAppFps(value) {
  const numericValue = Math.trunc(Number(value));
  if (Number.isFinite(numericValue) && numericValue > 0) {
    return numericValue;
  }
  return 60;
}

function normalizeFiniteNumber(value, fallback = 0) {
  const numericValue = Number(value);
  return Number.isFinite(numericValue) ? numericValue : fallback;
}

function normalizeOptionalFiniteNumber(value) {
  if (value == null) {
    return null;
  }
  const numericValue = Number(value);
  return Number.isFinite(numericValue) ? numericValue : null;
}

function normalizeOptionalPositiveInteger(value) {
  if (value == null) {
    return null;
  }
  const numericValue = Math.trunc(Number(value));
  return Number.isFinite(numericValue) && numericValue > 0 ? numericValue : null;
}

function normalizeOptionalPositiveNumber(value) {
  if (value == null) {
    return null;
  }
  const numericValue = Number(value);
  return Number.isFinite(numericValue) && numericValue > 0 ? numericValue : null;
}

function normalizeOptionalNavigatorHostField(value, fieldName) {
  if (value == null) {
    return null;
  }
  if (typeof value !== 'string') {
    throw new TypeError(`\`navigatorHost.${fieldName}\` must be a string when provided.`);
  }
  const normalized = value.trim();
  return normalized ? normalized : null;
}

function normalizeOptionalNavigatorHostLanguages(value) {
  if (value == null) {
    return [];
  }
  if (!Array.isArray(value)) {
    throw new TypeError('`navigatorHost.languages` must be an array when provided.');
  }
  return value
    .map((entry, index) => {
      if (typeof entry !== 'string') {
        throw new TypeError(`\`navigatorHost.languages[${index}]\` must be a string.`);
      }
      return entry.trim();
    })
    .filter(Boolean);
}

function normalizeNavigatorHostFields(serialNumber, platform, arch, languages, region) {
  const normalized = {
    serialNumber: normalizeOptionalNavigatorHostField(serialNumber, 'serialNumber'),
    platform: normalizeOptionalNavigatorHostField(platform, 'platform'),
    arch: normalizeOptionalNavigatorHostField(arch, 'arch'),
    languages: normalizeOptionalNavigatorHostLanguages(languages),
    region: normalizeOptionalNavigatorHostField(region, 'region'),
  };

  if (
    !normalized.serialNumber &&
    !normalized.platform &&
    !normalized.arch &&
    normalized.languages.length === 0 &&
    !normalized.region
  ) {
    return null;
  }

  return normalized;
}

export function configNavigatorHost(
  serialNumber = null,
  platform = null,
  arch = null,
  languages = null,
  region = null,
) {
  configuredNavigatorHost = normalizeNavigatorHostFields(
    serialNumber,
    platform,
    arch,
    languages,
    region,
  );
}

function ensureFetch(fetchImpl) {
  const resolved = fetchImpl || globalThis.fetch;
  if (typeof resolved !== 'function') {
    throw new Error('A fetch implementation is required for VFS loading.');
  }
  return resolved;
}

function trimTrailingSlash(value) {
  return value.replace(/\/+$/, '');
}

function ensureLeadingSlash(value) {
  if (!value) {
    return '/';
  }
  return value.startsWith('/') ? value : `/${value}`;
}

function normalizeBaseUrl(baseUrl) {
  if (typeof baseUrl !== 'string' || !baseUrl.trim()) {
    throw new Error('`baseUrl` must be a non-empty string.');
  }
  return trimTrailingSlash(baseUrl.trim());
}

function cloneUint8Array(value) {
  if (value instanceof Uint8Array) {
    return new Uint8Array(value);
  }
  if (ArrayBuffer.isView(value)) {
    return new Uint8Array(
      value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength),
    );
  }
  if (value instanceof ArrayBuffer) {
    return new Uint8Array(value.slice(0));
  }
  return null;
}

function toBundleBytes(filePath, value) {
  if (typeof value === 'string') {
    return new TextEncoder().encode(value);
  }

  const cloned = cloneUint8Array(value);
  if (cloned) {
    return cloned;
  }

  throw new TypeError(
    `Unsupported bundle file payload for \`${filePath}\`. Expected string, ArrayBuffer, or Uint8Array.`,
  );
}

function getEntries(files) {
  if (files instanceof Map) {
    return Array.from(files.entries());
  }
  if (Array.isArray(files)) {
    return files;
  }
  if (files && typeof files === 'object') {
    return Object.entries(files);
  }
  throw new TypeError('`files` must be a Map, entry array, or plain object.');
}

export function normalizeBundleFiles(files) {
  const normalized = new Map();
  for (const [filePath, value] of getEntries(files)) {
    if (typeof filePath !== 'string' || !filePath) {
      throw new TypeError('Bundle file paths must be non-empty strings.');
    }
    normalized.set(filePath, toBundleBytes(filePath, value));
  }
  return normalized;
}

export function normalizeHostPackages(hostPackages) {
  if (hostPackages == null) {
    return [];
  }
  if (!Array.isArray(hostPackages)) {
    throw new TypeError('`hostPackages` must be an array.');
  }
  return hostPackages.map((entry) => {
    if (!entry || typeof entry !== 'object') {
      throw new TypeError('Each `hostPackages` entry must be an object.');
    }
    const type = entry.type ?? 'bundle';
    if (type === 'bundle') {
      return {
        type: 'bundle',
        files: normalizeBundleFiles(entry.files),
      };
    }
    if (type === 'directory') {
      return {
        type: 'directory',
        path: String(entry.path || '').trim(),
      };
    }
    throw new TypeError(`Unsupported host package mount type \`${type}\`.`);
  });
}

export function normalizeHostFontsDir(hostFontsDir) {
  if (hostFontsDir == null) {
    return null;
  }
  if (typeof hostFontsDir !== 'string') {
    throw new TypeError('`hostFontsDir` must be a string.');
  }
  const normalized = hostFontsDir.trim();
  return normalized ? normalized : null;
}

export function serializeQuery(query) {
  if (query == null) {
    return null;
  }
  if (typeof query === 'string') {
    return query;
  }
  return JSON.stringify(query);
}

function normalizeHostTarget(target) {
  if (target === '_current' || target === '_blank' || target === '_widget') {
    return target;
  }
  throw new TypeError('`target` must be one of `_current`, `_blank`, or `_widget`.');
}

function normalizeOpenHostOptions(hostOptions) {
  if (hostOptions == null) {
    return null;
  }
  if (typeof hostOptions !== 'object' || Array.isArray(hostOptions)) {
    throw new TypeError('`hostOptions` must be an object when provided.');
  }

  const normalized = {};
  if (Object.prototype.hasOwnProperty.call(hostOptions, 'initialFocus')) {
    if (hostOptions.initialFocus !== 'focus' && hostOptions.initialFocus !== 'blur') {
      throw new TypeError('`hostOptions.initialFocus` must be either `focus` or `blur`.');
    }
    normalized.initialFocus = hostOptions.initialFocus;
  }
  if (Object.prototype.hasOwnProperty.call(hostOptions, 'openBlurTimeoutMs')) {
    if (hostOptions.openBlurTimeoutMs == null) {
      normalized.openBlurTimeoutMs = null;
    } else {
      const openBlurTimeoutMs = Number(hostOptions.openBlurTimeoutMs);
      if (
        !Number.isFinite(openBlurTimeoutMs) ||
        openBlurTimeoutMs < 0 ||
        !Number.isInteger(openBlurTimeoutMs)
      ) {
        throw new TypeError(
          '`hostOptions.openBlurTimeoutMs` must be a non-negative integer or null.',
        );
      }
      normalized.openBlurTimeoutMs = openBlurTimeoutMs;
    }
  }
  if (Object.prototype.hasOwnProperty.call(hostOptions, 'initialTarget')) {
    if (hostOptions.initialTarget == null) {
      normalized.initialTarget = null;
    } else {
      normalized.initialTarget = normalizeHostTarget(hostOptions.initialTarget);
    }
  }
  return normalized;
}

export function createProxyUrlResolver({ proxyUrl, targetSearchParam = 'url' }) {
  if (typeof proxyUrl !== 'string' || !proxyUrl.trim()) {
    throw new Error('`proxyUrl` must be a non-empty string.');
  }
  if (typeof targetSearchParam !== 'string' || !targetSearchParam.trim()) {
    throw new Error('`targetSearchParam` must be a non-empty string.');
  }

  const normalizedProxyUrl = proxyUrl.trim();
  const normalizedTargetSearchParam = targetSearchParam.trim();
  return (request) => {
    const target = new URL(normalizedProxyUrl, globalThis.location?.href || 'http://localhost');
    target.searchParams.set(normalizedTargetSearchParam, request.url);
    return {
      action: 'rewrite',
      url: target.toString(),
      method: request.method,
      headers: request.headers,
      body: request.body,
    };
  };
}

export function setInkRequestInterceptor(interceptor) {
  setInkRequestInterceptorBridge(interceptor);
}

export function clearInkRequestInterceptor() {
  clearInkRequestInterceptorBridge();
}

export function configureNetwork(options = {}) {
  if (options.interceptor) {
    setInkRequestInterceptor(options.interceptor);
    return;
  }
  if (options.proxyUrl) {
    setInkRequestInterceptor(createProxyUrlResolver(options));
    return;
  }
  if (options.clear) {
    clearInkRequestInterceptor();
  }
}

async function buildHttpError(response, bodyReader = (res) => res.text()) {
  let details = response.statusText;
  try {
    details = await bodyReader(response);
  } catch {
    // Keep the default status text when the body cannot be read.
  }
  const message = `Request failed with ${response.status} ${response.statusText}: ${String(details).slice(0, 200)}`;
  return new Error(message);
}

export function createVfsUrls({ baseUrl, appId }) {
  if (typeof appId !== 'string' || !appId.trim()) {
    throw new Error('`appId` must be a non-empty string.');
  }

  const normalizedBaseUrl = normalizeBaseUrl(baseUrl);
  const encodedAppId = encodeURIComponent(appId.trim());

  return {
    manifestUrl: `${normalizedBaseUrl}/apps/${encodedAppId}/manifest`,
    fileUrl(filePath) {
      if (typeof filePath !== 'string' || !filePath) {
        throw new Error('`filePath` must be a non-empty string.');
      }
      const segments = filePath
        .split('/')
        .filter(Boolean)
        .map((segment) => encodeURIComponent(segment));
      return `${normalizedBaseUrl}/apps/${encodedAppId}/files/${segments.join('/')}`;
    },
  };
}

export async function loadBundleFromVfs({
  appId,
  baseUrl,
  fetch: fetchImpl,
  signal,
  headers,
  requestInterceptor,
}) {
  const resolvedFetch = ensureFetch(fetchImpl);
  const wrappedFetch = async (url, init) =>
    fetchWithInkRequestInterceptor(url, init, {
      fetch: resolvedFetch,
      interceptor: requestInterceptor || null,
      metadata: {
        kind: 'vfs',
        appId,
      },
    });
  const { manifestUrl, fileUrl } = createVfsUrls({ baseUrl, appId });

  const manifestResponse = await wrappedFetch(manifestUrl, {
    method: 'GET',
    headers,
    signal,
  });
  if (!manifestResponse.ok) {
    throw await buildHttpError(manifestResponse);
  }

  const manifest = await manifestResponse.json();
  if (!manifest || !Array.isArray(manifest.files)) {
    throw new Error('VFS manifest must include a `files` array.');
  }

  const files = new Map();
  await Promise.all(
    manifest.files.map(async (entry) => {
      if (!entry || typeof entry.path !== 'string' || !entry.path) {
        throw new Error('Each VFS manifest entry must include a non-empty `path`.');
      }

      const response = await wrappedFetch(fileUrl(entry.path), {
        method: 'GET',
        headers,
        signal,
      });
      if (!response.ok) {
        throw await buildHttpError(response);
      }

      files.set(entry.path, new Uint8Array(await response.arrayBuffer()));
    }),
  );

  return {
    appId: manifest.appId || appId,
    manifest,
    files,
  };
}

function normalizeKeyboardCode(event) {
  return event.code || event.key || 'Unidentified';
}

function getPointerPosition(canvas, event) {
  const rect = canvas.getBoundingClientRect();
  const scaleX = rect.width > 0 ? canvas.width / rect.width : 1;
  const scaleY = rect.height > 0 ? canvas.height / rect.height : 1;
  return {
    x: (event.clientX - rect.left) * scaleX,
    y: (event.clientY - rect.top) * scaleY,
  };
}

function ensureAnimationFrameApi() {
  if (typeof globalThis.requestAnimationFrame !== 'function') {
    return {
      request(callback) {
        return setTimeout(() => callback(Date.now()), 16);
      },
      cancel(handle) {
        clearTimeout(handle);
      },
    };
  }

  return {
    request(callback) {
      return globalThis.requestAnimationFrame(callback);
    },
    cancel(handle) {
      globalThis.cancelAnimationFrame(handle);
    },
  };
}

function createDomCanvas(width, height) {
  const documentRef = globalThis.document;
  if (!documentRef || typeof documentRef.createElement !== 'function') {
    throw new Error(
      'OffscreenCanvas surfaces currently require a browser document to create a backing HTML canvas.',
    );
  }

  const canvas = documentRef.createElement('canvas');
  canvas.width = Math.max(1, Number(width) || 1);
  canvas.height = Math.max(1, Number(height) || 1);
  return canvas;
}

function normalizeHostMessageMetadata(origin, lastEventId) {
  if (typeof origin !== 'string') {
    throw new TypeError('`origin` must be a string.');
  }
  if (typeof lastEventId !== 'string') {
    throw new TypeError('`lastEventId` must be a string.');
  }
  return { origin, lastEventId };
}

function normalizeConnectivityStatus(status) {
  if (typeof status !== 'string') {
    throw new TypeError('`status` must be a string.');
  }
  const normalized = status.trim().toLowerCase();
  if (normalized !== 'online' && normalized !== 'offline') {
    throw new TypeError('`status` must be either `online` or `offline`.');
  }
  return normalized;
}

function normalizeHostMessageFormat(format) {
  if (format == null) {
    return 'auto';
  }

  if (typeof format !== 'string') {
    throw new TypeError('`format` must be a string when provided.');
  }

  const normalized = format.trim().toLowerCase();
  if (normalized === '' || normalized === 'auto') {
    return 'auto';
  }
  if (normalized === 'json') {
    return 'json';
  }
  if (normalized === 'jsonl') {
    return 'jsonl';
  }

  throw new TypeError('`format` must be one of `auto`, `json`, or `jsonl`.');
}

function serializeHostMessagePayload(dataOrJson, format) {
  if (typeof dataOrJson === 'string') {
    return {
      payloadText: dataOrJson,
      format: normalizeHostMessageFormat(format),
    };
  }

  let payloadJson;
  try {
    payloadJson = JSON.stringify(dataOrJson);
  } catch {
    throw new TypeError('dispatchMessageEvent(data) requires a JSON-serializable value.');
  }
  if (typeof payloadJson !== 'string') {
    throw new TypeError('dispatchMessageEvent(data) requires a JSON-serializable value.');
  }
  return {
    payloadText: payloadJson,
    format: 'json',
  };
}

function cloneSurfaceDescriptor(surface) {
  if (!surface) {
    return null;
  }
  return { ...surface };
}

function normalizeSurfaceDescriptor(surface) {
  if (!surface) {
    return null;
  }

  if (typeof surface !== 'object') {
    throw new TypeError('`surface` must be an object.');
  }

  if (surface.type === 'html-canvas') {
    if (!surface.canvas || typeof surface.canvas.getBoundingClientRect !== 'function') {
      throw new TypeError('`surface.canvas` must be an HTMLCanvasElement-like object.');
    }
    return {
      type: 'html-canvas',
      canvas: surface.canvas,
    };
  }

  if (surface.type === 'offscreen-canvas') {
    if (!surface.canvas || typeof surface.canvas.getContext !== 'function') {
      throw new TypeError('`surface.canvas` must be an OffscreenCanvas-like object.');
    }
    return {
      type: 'offscreen-canvas',
      canvas: surface.canvas,
    };
  }

  throw new TypeError(`Unsupported surface type: ${String(surface.type)}`);
}

function normalizeLayoutMode(value) {
  if (value == null || value === '') {
    return 'bounded';
  }
  if (value === 'bounded' || value === 'width-constrained-auto-height') {
    return value;
  }
  throw new TypeError('`layoutMode` must be either `bounded` or `width-constrained-auto-height`.');
}

function normalizeThemeName(value) {
  if (value == null) {
    return null;
  }
  const themeName = String(value).trim();
  return themeName ? themeName : null;
}

function normalizeThemeCss(value) {
  if (value == null) {
    return null;
  }
  const themeCss = String(value).trim();
  return themeCss ? themeCss : null;
}

function isResizeOptions(value) {
  return value != null && typeof value === 'object' && !Array.isArray(value);
}

function normalizeStringMap(value, optionName) {
  if (value == null) {
    return {};
  }
  if (typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError(`\`${optionName}\` must be a plain object when provided.`);
  }
  return Object.fromEntries(
    Object.entries(value).map(([key, entryValue]) => [String(key), String(entryValue)]),
  );
}

function normalizeHostLanguageModelConfig(config) {
  if (config == null || typeof config !== 'object' || Array.isArray(config)) {
    throw new TypeError('`languageModel.getConfig()` must resolve to a config object.');
  }

  const endpoint = String(config.endpoint || '').trim();
  const apiStyle = String(config.apiStyle || '').trim();
  if (!endpoint) {
    throw new TypeError('`languageModel.getConfig()` must provide a non-empty `endpoint`.');
  }
  if (!apiStyle) {
    throw new TypeError('`languageModel.getConfig()` must provide a non-empty `apiStyle`.');
  }

  return {
    endpoint,
    defaultModel: config.defaultModel == null ? null : String(config.defaultModel).trim() || null,
    apiStyle,
    headers: normalizeStringMap(config.headers, 'languageModel.headers'),
    shareVendorHeaders: Boolean(config.shareVendorHeaders),
    vendorHeadersService:
      config.vendorHeadersService == null
        ? null
        : String(config.vendorHeadersService).trim() || null,
  };
}

function ensureHostCapabilitiesObject(value) {
  if (value == null) {
    return null;
  }
  if (typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError('`hostCapabilities` must be an object or null.');
  }
  return value;
}

function parseHostCapabilityRequest(requestJson, capability, method) {
  if (typeof requestJson !== 'string') {
    throw new TypeError(`Host capability ${capability}.${method} requires a JSON request string.`);
  }
  try {
    return JSON.parse(requestJson);
  } catch {
    throw new Error(`Host capability ${capability}.${method} received invalid request JSON.`);
  }
}

function serializeIpcResponseData(responseData) {
  const json = JSON.stringify(responseData);
  if (typeof json !== 'string') {
    throw new TypeError('Host capability handler must return JSON-serializable response data.');
  }
  return json;
}

function createSuccessResponseData() {
  return { type: 'Success' };
}

function serializeSuccessResponse() {
  return serializeIpcResponseData(createSuccessResponseData());
}

function serializePaymentCanMakePaymentResponse(result) {
  return serializeIpcResponseData({
    type: 'Payment',
    data: {
      type: 'CanMakePayment',
      data: Boolean(result),
    },
  });
}

function normalizePaymentShowResponse(result, request) {
  if (result == null || typeof result !== 'object' || Array.isArray(result)) {
    throw new TypeError('`payment.show()` must resolve to a PaymentResponse-like object.');
  }

  const fallbackRequestId = request?.requestId;
  const requestId = String(result.requestId ?? fallbackRequestId ?? '').trim();
  if (!requestId) {
    throw new TypeError('`payment.show()` must provide a non-empty `requestId`.');
  }

  const methodName = String(result.methodName ?? '').trim();
  if (!methodName) {
    throw new TypeError('`payment.show()` must provide a non-empty `methodName`.');
  }

  return {
    requestId,
    methodName,
    payerName: result.payerName ?? undefined,
    payerEmail: result.payerEmail ?? undefined,
    payerPhone: result.payerPhone ?? undefined,
    shippingOption: result.shippingOption ?? undefined,
    shippingAddress: result.shippingAddress ?? undefined,
    details: result.details ?? null,
  };
}

function serializePaymentShowResponse(result, request) {
  return serializeIpcResponseData({
    type: 'Payment',
    data: {
      type: 'Show',
      data: normalizePaymentShowResponse(result, request),
    },
  });
}

function normalizeGeolocationCoordinates(result) {
  if (result == null || typeof result !== 'object' || Array.isArray(result)) {
    throw new TypeError(
      '`geolocation.getCurrentPosition()` must resolve to an object with `coords` and `timestamp`.',
    );
  }
  const coords = result.coords;
  if (coords == null || typeof coords !== 'object' || Array.isArray(coords)) {
    throw new TypeError(
      '`geolocation.getCurrentPosition()` must resolve to an object with a `coords` object.',
    );
  }
  return {
    latitude: normalizeFiniteNumber(coords.latitude, 0),
    longitude: normalizeFiniteNumber(coords.longitude, 0),
    accuracy: normalizeFiniteNumber(coords.accuracy, 0),
    altitude: normalizeOptionalFiniteNumber(coords.altitude),
    altitudeAccuracy: normalizeOptionalFiniteNumber(coords.altitudeAccuracy),
    heading: normalizeOptionalFiniteNumber(coords.heading),
    speed: normalizeOptionalFiniteNumber(coords.speed),
    timestamp: normalizeFiniteNumber(result.timestamp, 0),
  };
}

function serializeGeolocationPositionResponse(result) {
  const normalized = normalizeGeolocationCoordinates(result);
  return serializeIpcResponseData({
    type: 'Geolocation',
    data: {
      type: 'Position',
      data: {
        coords: {
          latitude: normalized.latitude,
          longitude: normalized.longitude,
          accuracy: normalized.accuracy,
          altitude: normalized.altitude,
          altitudeAccuracy: normalized.altitudeAccuracy,
          heading: normalized.heading,
          speed: normalized.speed,
        },
        timestamp: normalized.timestamp,
      },
    },
  });
}

function normalizeBatteryStatus(result, methodName = 'battery.start()') {
  if (result == null || typeof result !== 'object' || Array.isArray(result)) {
    throw new TypeError(`\`${methodName}\` must resolve to a battery status object.`);
  }
  return {
    charging: Boolean(result.charging),
    chargingTime: normalizeOptionalFiniteNumber(result.chargingTime),
    dischargingTime: normalizeOptionalFiniteNumber(result.dischargingTime),
    level: Math.max(0, Math.min(1, normalizeFiniteNumber(result.level, 1))),
  };
}

function serializeBatteryStatusResponse(result) {
  const normalized = normalizeBatteryStatus(result);
  return serializeIpcResponseData({
    type: 'Battery',
    data: {
      type: 'Status',
      data: {
        charging: normalized.charging,
        chargingTime: normalized.chargingTime,
        dischargingTime: normalized.dischargingTime,
        level: normalized.level,
      },
    },
  });
}

function serializeOpenServiceManifestResponse(result) {
  return serializeIpcResponseData({
    type: 'OpenService',
    data: {
      type: 'Manifest',
      data: result,
    },
  });
}

function serializeOpenServiceVendorHeadersResponse(result) {
  return serializeIpcResponseData({
    type: 'OpenService',
    data: {
      type: 'VendorHeaders',
      data: normalizeStringMap(result, 'openService.getVendorHeaders() result'),
    },
  });
}

function normalizePhotoResult(result) {
  if (result == null || typeof result !== 'object' || Array.isArray(result)) {
    throw new TypeError(
      '`media.takePhoto()` must resolve to an object with `data` and `mimeType`.',
    );
  }
  const mimeType = String(result.mimeType || '').trim();
  if (!mimeType) {
    throw new TypeError('`media.takePhoto()` must provide a non-empty `mimeType`.');
  }
  const data = cloneUint8Array(result.data ?? result.bytes ?? result.buffer);
  if (!data) {
    throw new TypeError(
      '`media.takePhoto()` must provide binary `data` as Uint8Array, ArrayBuffer, or TypedArray.',
    );
  }
  return { data, mimeType };
}

function normalizeMediaDeviceInfo(device, index) {
  if (device == null || typeof device !== 'object' || Array.isArray(device)) {
    throw new TypeError(
      `\`media.enumerateDevices()\` must return an array of media device objects. Invalid entry at index ${index}.`,
    );
  }

  const deviceId = String(device.deviceId || '').trim();
  if (!deviceId) {
    throw new TypeError(
      `\`media.enumerateDevices()\` entry ${index} must provide a non-empty \`deviceId\`.`,
    );
  }

  const kind = String(device.kind || '').trim();
  if (kind !== 'audioinput' && kind !== 'videoinput') {
    throw new TypeError(
      `\`media.enumerateDevices()\` entry ${index} must provide kind \`audioinput\` or \`videoinput\`.`,
    );
  }

  return {
    deviceId,
    kind,
    label: String(device.label || ''),
    groupId: device.groupId == null ? '' : String(device.groupId),
  };
}

function serializeEnumerateDevicesResponse(result) {
  if (!Array.isArray(result)) {
    throw new TypeError('`media.enumerateDevices()` must resolve to an array.');
  }

  return serializeIpcResponseData({
    type: 'Media',
    data: {
      type: 'EnumerateDevicesResult',
      data: result.map((device, index) => normalizeMediaDeviceInfo(device, index)),
    },
  });
}

function normalizeMediaTrackSettings(settings, methodName) {
  if (settings == null || typeof settings !== 'object' || Array.isArray(settings)) {
    throw new TypeError(`\`${methodName}\` must provide a \`settings\` object for each track.`);
  }

  const normalized = {};
  const deviceId = settings.deviceId == null ? null : String(settings.deviceId).trim();
  const facingMode =
    settings.facingMode == null ? null : String(settings.facingMode).trim() || null;
  const sampleRate = normalizeOptionalPositiveInteger(settings.sampleRate);
  const channelCount = normalizeOptionalPositiveInteger(settings.channelCount);
  const width = normalizeOptionalPositiveInteger(settings.width);
  const height = normalizeOptionalPositiveInteger(settings.height);
  const frameRate = normalizeOptionalPositiveNumber(settings.frameRate);

  if (deviceId) {
    normalized.deviceId = deviceId;
  }
  if (sampleRate != null) {
    normalized.sampleRate = sampleRate;
  }
  if (channelCount != null) {
    normalized.channelCount = channelCount;
  }
  if (settings.echoCancellation != null) {
    normalized.echoCancellation = Boolean(settings.echoCancellation);
  }
  if (facingMode) {
    normalized.facingMode = facingMode;
  }
  if (width != null) {
    normalized.width = width;
  }
  if (height != null) {
    normalized.height = height;
  }
  if (frameRate != null) {
    normalized.frameRate = frameRate;
  }

  return normalized;
}

function normalizeMediaTrackDescriptor(track, index, methodName) {
  if (track == null || typeof track !== 'object' || Array.isArray(track)) {
    throw new TypeError(
      `\`${methodName}\` must provide track objects. Invalid entry at index ${index}.`,
    );
  }

  const trackId = String(track.trackId || '').trim();
  if (!trackId) {
    throw new TypeError(`\`${methodName}\` track ${index} must provide a non-empty \`trackId\`.`);
  }

  const kind = String(track.kind || '').trim();
  if (kind !== 'audio' && kind !== 'video') {
    throw new TypeError(
      `\`${methodName}\` track ${index} must provide kind \`audio\` or \`video\`.`,
    );
  }

  return {
    trackId,
    kind,
    label: String(track.label || ''),
    enabled: Boolean(track.enabled),
    muted: Boolean(track.muted),
    settings: normalizeMediaTrackSettings(track.settings, methodName),
  };
}

function normalizeMediaStreamDescriptor(result, methodName = 'media.getUserMedia()') {
  if (result == null || typeof result !== 'object' || Array.isArray(result)) {
    throw new TypeError(`\`${methodName}\` must resolve to a media stream descriptor object.`);
  }

  const streamId = String(result.streamId || '').trim();
  if (!streamId) {
    throw new TypeError(`\`${methodName}\` must provide a non-empty \`streamId\`.`);
  }
  if (!Array.isArray(result.tracks)) {
    throw new TypeError(`\`${methodName}\` must provide a \`tracks\` array.`);
  }

  return {
    streamId,
    tracks: result.tracks.map((track, index) =>
      normalizeMediaTrackDescriptor(track, index, methodName),
    ),
  };
}

function serializeGetUserMediaResponse(result) {
  return serializeIpcResponseData({
    type: 'Media',
    data: {
      type: 'GetUserMediaResult',
      data: normalizeMediaStreamDescriptor(result),
    },
  });
}

function normalizeMediaRecorderDescriptor(result) {
  if (result == null || typeof result !== 'object' || Array.isArray(result)) {
    throw new TypeError(
      '`media.createMediaRecorder()` must resolve to a media recorder descriptor object.',
    );
  }

  const recorderId = String(result.recorderId || '').trim();
  if (!recorderId) {
    throw new TypeError('`media.createMediaRecorder()` must provide a non-empty `recorderId`.');
  }

  const mimeType = String(result.mimeType || '').trim();
  if (!mimeType) {
    throw new TypeError('`media.createMediaRecorder()` must provide a non-empty `mimeType`.');
  }

  return { recorderId, mimeType };
}

function serializeCreateMediaRecorderResponse(result) {
  return serializeIpcResponseData({
    type: 'Media',
    data: {
      type: 'CreateMediaRecorderResult',
      data: normalizeMediaRecorderDescriptor(result),
    },
  });
}

function serializeTakePhotoResponse(result) {
  const photo = normalizePhotoResult(result);
  return serializeIpcResponseData({
    type: 'Media',
    data: {
      type: 'TakePhotoResult',
      data: [Array.from(photo.data), photo.mimeType],
    },
  });
}

function serializeLanguageModelConfigResponse(result) {
  return serializeIpcResponseData({
    type: 'LanguageModel',
    data: {
      type: 'Config',
      data: normalizeHostLanguageModelConfig(result),
    },
  });
}

function normalizeSpeechAudioFormat(format) {
  return ['pcm', 'mp3', 'ogg_opus'].includes(format) ? format : 'ogg_opus';
}

function serializeSpeechSynthesisStartedResponse(result) {
  if (result == null || typeof result !== 'object' || Array.isArray(result)) {
    throw new TypeError('`speech.synthesize()` must resolve to synthesis task metadata.');
  }
  const synthesisId = String(result.synthesisId || '').trim();
  const mimeType = String(result.mimeType || '').trim();
  const audioConfig = result.audioConfig;
  if (!synthesisId || !mimeType || audioConfig == null || typeof audioConfig !== 'object') {
    throw new TypeError(
      '`speech.synthesize()` must provide `synthesisId`, `mimeType`, and `audioConfig`.',
    );
  }
  return serializeIpcResponseData({
    type: 'Speech',
    data: {
      type: 'SynthesisStarted',
      data: {
        synthesisId,
        mimeType,
        audioConfig: {
          format: normalizeSpeechAudioFormat(audioConfig.format),
          sampleRate: Math.max(1, Math.trunc(Number(audioConfig.sampleRate) || 0)),
          channels: Math.max(1, Math.min(2, Math.trunc(Number(audioConfig.channels) || 0))),
          sampleFormat: audioConfig.sampleFormat === 's16' ? 's16' : null,
        },
        language: String(result.language || ''),
        subtitleGranularity:
          result.subtitleGranularity === 'word' || result.subtitleGranularity === 'none'
            ? result.subtitleGranularity
            : 'sentence',
      },
    },
  });
}

const HOST_CAPABILITY_SERIALIZERS = {
  speech: {
    speak: () => serializeSuccessResponse(),
    synthesize: (result) => serializeSpeechSynthesisStartedResponse(result),
    abortSynthesis: () => serializeSuccessResponse(),
    startRecognition: () => serializeSuccessResponse(),
    stopRecognition: () => serializeSuccessResponse(),
    abortRecognition: () => serializeSuccessResponse(),
  },
  geolocation: {
    getCurrentPosition: (result) => serializeGeolocationPositionResponse(result),
    watchPosition: () => serializeSuccessResponse(),
    clearWatch: () => serializeSuccessResponse(),
  },
  battery: {
    start: (result) => serializeBatteryStatusResponse(result),
    stop: () => serializeSuccessResponse(),
  },
  payment: {
    canMakePayment: (result) => serializePaymentCanMakePaymentResponse(result),
    show: (result, request) => serializePaymentShowResponse(result, request),
    abort: () => serializeSuccessResponse(),
    complete: () => serializeSuccessResponse(),
  },
  absoluteOrientation: {
    start: () => serializeSuccessResponse(),
    stop: () => serializeSuccessResponse(),
  },
  accelerometer: {
    start: () => serializeSuccessResponse(),
    stop: () => serializeSuccessResponse(),
  },
  gyroscope: {
    start: () => serializeSuccessResponse(),
    stop: () => serializeSuccessResponse(),
  },
  magnetometer: {
    start: () => serializeSuccessResponse(),
    stop: () => serializeSuccessResponse(),
  },
  media: {
    enumerateDevices: (result) => serializeEnumerateDevicesResponse(result),
    getUserMedia: (result) => serializeGetUserMediaResponse(result),
    stopMediaTrack: () => serializeSuccessResponse(),
    createMediaRecorder: (result) => serializeCreateMediaRecorderResponse(result),
    startMediaRecorder: () => serializeSuccessResponse(),
    pauseMediaRecorder: () => serializeSuccessResponse(),
    resumeMediaRecorder: () => serializeSuccessResponse(),
    requestMediaRecorderData: () => serializeSuccessResponse(),
    stopMediaRecorder: () => serializeSuccessResponse(),
    takePhoto: (result) => serializeTakePhotoResponse(result),
  },
  openService: {
    getManifest: (result) => serializeOpenServiceManifestResponse(result),
    getVendorHeaders: (result) => serializeOpenServiceVendorHeadersResponse(result),
  },
  languageModel: {
    getConfig: (result) => serializeLanguageModelConfigResponse(result),
  },
};

function getBrowserMediaDevices() {
  const mediaDevices = globalThis.navigator?.mediaDevices;
  if (!mediaDevices || typeof mediaDevices !== 'object') {
    return null;
  }
  return mediaDevices;
}

function getBrowserMediaRecorderConstructor() {
  return typeof globalThis.MediaRecorder === 'function' ? globalThis.MediaRecorder : null;
}

function dispatchHostCapabilityCustomEvent(target, eventType, detail) {
  if (!target || typeof target.dispatchEvent !== 'function' || typeof CustomEvent !== 'function') {
    return;
  }
  target.dispatchEvent(new CustomEvent(eventType, { detail }));
}

function normalizeBrowserTrackConstraints(constraints) {
  if (!constraints || typeof constraints !== 'object') {
    return true;
  }

  const normalized = {};
  if (constraints.deviceId) {
    normalized.deviceId = constraints.deviceId;
  }
  if (constraints.sampleRate != null) {
    normalized.sampleRate = constraints.sampleRate;
  }
  if (constraints.channelCount != null) {
    normalized.channelCount = constraints.channelCount;
  }
  if (constraints.echoCancellation != null) {
    normalized.echoCancellation = Boolean(constraints.echoCancellation);
  }
  if (constraints.facingMode) {
    normalized.facingMode = constraints.facingMode;
  }
  if (constraints.width != null) {
    normalized.width = constraints.width;
  }
  if (constraints.height != null) {
    normalized.height = constraints.height;
  }
  if (constraints.frameRate != null) {
    normalized.frameRate = constraints.frameRate;
  }
  return Object.keys(normalized).length > 0 ? normalized : true;
}

function normalizeBrowserMediaTrackDescriptor(track) {
  const settings = typeof track?.getSettings === 'function' ? track.getSettings() || {} : {};
  const normalizedSettings = {};
  if (settings.deviceId) {
    normalizedSettings.deviceId = String(settings.deviceId);
  }
  if (settings.sampleRate != null) {
    normalizedSettings.sampleRate = Math.max(1, Math.trunc(Number(settings.sampleRate) || 0));
  }
  if (settings.channelCount != null) {
    normalizedSettings.channelCount = Math.max(1, Math.trunc(Number(settings.channelCount) || 0));
  }
  if (settings.echoCancellation != null) {
    normalizedSettings.echoCancellation = Boolean(settings.echoCancellation);
  }
  if (settings.facingMode) {
    normalizedSettings.facingMode = String(settings.facingMode);
  }
  if (settings.width != null) {
    normalizedSettings.width = Math.max(1, Math.trunc(Number(settings.width) || 0));
  }
  if (settings.height != null) {
    normalizedSettings.height = Math.max(1, Math.trunc(Number(settings.height) || 0));
  }
  if (settings.frameRate != null) {
    normalizedSettings.frameRate = Math.max(0, Number(settings.frameRate) || 0);
  }

  return {
    trackId: String(track?.id || ''),
    kind: track?.kind === 'video' ? 'video' : 'audio',
    label: String(track?.label || ''),
    enabled: track?.enabled !== false,
    muted: Boolean(track?.muted),
    settings: normalizedSettings,
  };
}

async function blobToUint8Array(blob) {
  if (!blob || typeof blob.arrayBuffer !== 'function') {
    return new Uint8Array();
  }
  const buffer = await blob.arrayBuffer();
  return new Uint8Array(buffer);
}

const browserMediaStreamsSymbol = Symbol.for('com.rokid.jsui.ink.mediaStreams');

function getBrowserMediaStreamRegistry() {
  const current = globalThis[browserMediaStreamsSymbol];
  if (current instanceof Map) {
    return current;
  }
  const registry = new Map();
  globalThis[browserMediaStreamsSymbol] = registry;
  return registry;
}

function oggCrc(bytes) {
  let crc = 0;
  for (const byte of bytes) {
    crc ^= byte << 24;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc << 1) ^ (crc & 0x80000000 ? 0x04c11db7 : 0);
    }
  }
  return crc >>> 0;
}

function createOggPage(packet, serial, sequence, granule, headerType) {
  const segments = [];
  let remaining = packet.byteLength;
  while (remaining >= 255) {
    segments.push(255);
    remaining -= 255;
  }
  segments.push(remaining);
  const page = new Uint8Array(27 + segments.length + packet.byteLength);
  const view = new DataView(page.buffer);
  page.set([79, 103, 103, 83], 0);
  page[5] = headerType;
  view.setBigUint64(6, BigInt(granule), true);
  view.setUint32(14, serial, true);
  view.setUint32(18, sequence, true);
  page[26] = segments.length;
  page.set(segments, 27);
  page.set(packet, 27 + segments.length);
  view.setUint32(22, oggCrc(page), true);
  return page;
}

function createOpusHead(sampleRate, channels) {
  const packet = new Uint8Array(19);
  const view = new DataView(packet.buffer);
  packet.set(new TextEncoder().encode('OpusHead'));
  packet[8] = 1;
  packet[9] = channels;
  view.setUint16(10, 312, true);
  view.setUint32(12, sampleRate, true);
  return packet;
}

function createOpusTags() {
  const vendor = new TextEncoder().encode('Ink Web');
  const packet = new Uint8Array(16 + vendor.length);
  const view = new DataView(packet.buffer);
  packet.set(new TextEncoder().encode('OpusTags'));
  view.setUint32(8, vendor.length, true);
  packet.set(vendor, 12);
  return packet;
}

class BrowserOggOpusMuxer {
  constructor(sampleRate, channels) {
    this.serial = Math.floor(Math.random() * 0xffffffff) >>> 0;
    this.sequence = 0;
    this.pending = null;
    this.headers = [
      createOggPage(createOpusHead(sampleRate, channels), this.serial, this.sequence++, 0, 2),
      createOggPage(createOpusTags(), this.serial, this.sequence++, 0, 0),
    ];
  }

  add(packet, granule) {
    const output = this.headers.splice(0);
    if (this.pending) {
      output.push(
        createOggPage(this.pending.packet, this.serial, this.sequence++, this.pending.granule, 0),
      );
    }
    this.pending = { packet, granule };
    return output;
  }

  finish() {
    const output = this.headers.splice(0);
    if (this.pending) {
      output.push(
        createOggPage(this.pending.packet, this.serial, this.sequence++, this.pending.granule, 4),
      );
      this.pending = null;
    }
    return output;
  }
}

class BrowserPcmMediaRecorder extends EventTarget {
  constructor(stream, mimeType) {
    super();
    this.stream = stream;
    this.mimeType = mimeType;
    this.state = 'inactive';
    this.chunks = [];
    this.timesliceTimer = null;
  }

  _emitData(parts, type, isLastChunk = false) {
    const event = new Event('dataavailable');
    Object.defineProperties(event, {
      data: { value: new Blob(parts, { type }) },
      inkIsLastChunk: { value: isLastChunk },
    });
    this.dispatchEvent(event);
  }

  _emitError(error) {
    const event = new Event('error');
    Object.defineProperty(event, 'error', { value: error });
    this.dispatchEvent(event);
  }

  start(timeslice) {
    if (this.state !== 'inactive') {
      throw new DOMException('MediaRecorder is not inactive', 'InvalidStateError');
    }
    this.state = 'recording';
    this._startCapture().catch((error) => {
      this.state = 'inactive';
      this._emitError(error);
    });
    if (timeslice > 0) {
      this.timesliceTimer = setInterval(() => this.requestData(), timeslice);
    }
  }

  async _startCapture() {
    const AudioContextConstructor = globalThis.AudioContext || globalThis.webkitAudioContext;
    if (typeof AudioContextConstructor !== 'function') {
      throw new DOMException('Web Audio capture is unavailable', 'NotSupportedError');
    }
    const audioTrack = this.stream.getAudioTracks?.()[0];
    const settings = audioTrack?.getSettings?.() || {};
    const requestedSampleRate = Math.max(8000, Math.trunc(Number(settings.sampleRate) || 48000));
    this.channels = Math.min(2, Math.max(1, Math.trunc(Number(settings.channelCount) || 1)));
    this.context = new AudioContextConstructor({ sampleRate: requestedSampleRate });
    this.sampleRate = this.context.sampleRate;
    this.source = this.context.createMediaStreamSource(this.stream);
    this.processor = this.context.createScriptProcessor(4096, this.channels, this.channels);
    this.silentGain = this.context.createGain();
    this.silentGain.gain.value = 0;
    this.processor.onaudioprocess = (event) => this._processAudio(event.inputBuffer);
    this.source.connect(this.processor);
    this.processor.connect(this.silentGain);
    this.silentGain.connect(this.context.destination);
    if (this.mimeType === 'audio/ogg;codecs=opus') {
      await this._startOpusEncoder();
    }
    await this.context.resume();
    this.dispatchEvent(new Event('start'));
  }

  async _startOpusEncoder() {
    if (
      typeof globalThis.AudioEncoder !== 'function' ||
      typeof globalThis.AudioData !== 'function'
    ) {
      throw new DOMException('WebCodecs Opus encoder is unavailable', 'NotSupportedError');
    }
    const config = {
      codec: 'opus',
      sampleRate: this.sampleRate,
      numberOfChannels: this.channels,
      bitrate: 64000,
    };
    if (typeof globalThis.AudioEncoder.isConfigSupported === 'function') {
      const support = await globalThis.AudioEncoder.isConfigSupported(config);
      if (!support?.supported) {
        throw new DOMException('WebCodecs Opus configuration is unsupported', 'NotSupportedError');
      }
    }
    this.oggMuxer = new BrowserOggOpusMuxer(this.sampleRate, this.channels);
    this.encoder = new globalThis.AudioEncoder({
      output: (chunk) => {
        const packet = new Uint8Array(chunk.byteLength);
        chunk.copyTo(packet);
        const duration = Number(chunk.duration) || 0;
        const granule = 312 + Math.round(((Number(chunk.timestamp) + duration) * 48000) / 1000000);
        const pages = this.oggMuxer.add(packet, granule);
        if (pages.length > 0) {
          this._emitData(pages, this.mimeType, false);
        }
      },
      error: (error) => this._emitError(error),
    });
    this.encoder.configure(config);
    this.nextTimestamp = 0;
  }

  _processAudio(input) {
    if (this.state !== 'recording') {
      return;
    }
    const frames = input.length;
    if (this.encoder) {
      const planar = new Float32Array(frames * this.channels);
      for (let channel = 0; channel < this.channels; channel += 1) {
        planar.set(
          input.getChannelData(Math.min(channel, input.numberOfChannels - 1)),
          channel * frames,
        );
      }
      const data = new globalThis.AudioData({
        format: 'f32-planar',
        sampleRate: this.sampleRate,
        numberOfFrames: frames,
        numberOfChannels: this.channels,
        timestamp: this.nextTimestamp,
        data: planar,
      });
      this.nextTimestamp += Math.round((frames * 1000000) / this.sampleRate);
      this.encoder.encode(data);
      data.close();
      return;
    }
    const pcm = new Int16Array(frames * this.channels);
    for (let frame = 0; frame < frames; frame += 1) {
      for (let channel = 0; channel < this.channels; channel += 1) {
        const samples = input.getChannelData(Math.min(channel, input.numberOfChannels - 1));
        const sample = Math.max(-1, Math.min(1, samples[frame]));
        pcm[frame * this.channels + channel] = sample < 0 ? sample * 32768 : sample * 32767;
      }
    }
    this.chunks.push(new Uint8Array(pcm.buffer));
  }

  pause() {
    if (this.state !== 'recording')
      throw new DOMException('MediaRecorder is not recording', 'InvalidStateError');
    this.state = 'paused';
    this.dispatchEvent(new Event('pause'));
  }

  resume() {
    if (this.state !== 'paused')
      throw new DOMException('MediaRecorder is not paused', 'InvalidStateError');
    this.state = 'recording';
    this.dispatchEvent(new Event('resume'));
  }

  requestData() {
    if (this.encoder || this.chunks.length === 0) return;
    this._emitData(this.chunks.splice(0), 'audio/pcm', false);
  }

  stop() {
    if (this.state === 'inactive') return;
    this.state = 'inactive';
    clearInterval(this.timesliceTimer);
    this.timesliceTimer = null;
    this.processor?.disconnect();
    this.source?.disconnect();
    this.silentGain?.disconnect();
    this._finishCapture().catch((error) => this._emitError(error));
  }

  async _finishCapture() {
    if (this.encoder) {
      await this.encoder.flush();
      const pages = this.oggMuxer.finish();
      this._emitData(pages, this.mimeType, true);
      this.encoder.close();
    } else {
      this._emitData(this.chunks.splice(0), 'audio/pcm', true);
    }
    await this.context?.close();
    this.dispatchEvent(new Event('stop'));
  }
}

function createDefaultBrowserMediaCapability(eventTarget) {
  const mediaDevices = getBrowserMediaDevices();
  const BrowserMediaRecorder = getBrowserMediaRecorderConstructor();
  if (!mediaDevices || typeof mediaDevices.getUserMedia !== 'function') {
    return null;
  }

  const streams = getBrowserMediaStreamRegistry();
  const recorders = new Map();
  let deviceChangeBound = false;

  const dispatchDevicesChanged = async () => {
    if (typeof mediaDevices.enumerateDevices !== 'function') {
      return;
    }
    try {
      const devices = await mediaDevices.enumerateDevices();
      dispatchHostCapabilityCustomEvent(eventTarget, 'media.mediaDevicesChanged', {
        targetId: 'media-devices',
        devices: Array.isArray(devices)
          ? devices.map((device) => ({
              deviceId: String(device?.deviceId || ''),
              kind: String(device?.kind || ''),
              label: String(device?.label || ''),
              groupId: String(device?.groupId || ''),
            }))
          : [],
      });
    } catch (error) {
      console.error('InkView browser media backend failed to enumerate devices change.', error);
    }
  };

  const ensureDeviceChangeListener = () => {
    if (deviceChangeBound || typeof mediaDevices.addEventListener !== 'function') {
      return;
    }
    mediaDevices.addEventListener('devicechange', dispatchDevicesChanged);
    deviceChangeBound = true;
  };

  return {
    async enumerateDevices() {
      ensureDeviceChangeListener();
      if (typeof mediaDevices.enumerateDevices !== 'function') {
        return [];
      }
      const devices = await mediaDevices.enumerateDevices();
      return Array.isArray(devices)
        ? devices.map((device) => ({
            deviceId: String(device?.deviceId || ''),
            kind: String(device?.kind || ''),
            label: String(device?.label || ''),
            groupId: String(device?.groupId || ''),
          }))
        : [];
    },
    async getUserMedia(request) {
      ensureDeviceChangeListener();
      const browserConstraints = {
        audio: request?.constraints?.audio
          ? normalizeBrowserTrackConstraints(request.constraints.audio)
          : false,
        video: request?.constraints?.video
          ? normalizeBrowserTrackConstraints(request.constraints.video)
          : false,
      };
      const stream = await mediaDevices.getUserMedia(browserConstraints);
      streams.set(request.streamId, stream);

      const tracks = stream.getTracks().map((track) => {
        const streamId = request.streamId;
        const trackId = String(track?.id || '');
        if (typeof track?.addEventListener === 'function') {
          track.addEventListener('ended', () => {
            dispatchHostCapabilityCustomEvent(eventTarget, 'media.mediaTrackEnded', {
              targetId: trackId,
              streamId,
              trackId,
            });
          });
          track.addEventListener('mute', () => {
            dispatchHostCapabilityCustomEvent(eventTarget, 'media.mediaTrackMuted', {
              targetId: trackId,
              streamId,
              trackId,
            });
          });
          track.addEventListener('unmute', () => {
            dispatchHostCapabilityCustomEvent(eventTarget, 'media.mediaTrackUnmuted', {
              targetId: trackId,
              streamId,
              trackId,
            });
          });
        }
        dispatchHostCapabilityCustomEvent(eventTarget, 'media.mediaTrackStarted', {
          targetId: trackId,
          streamId,
          trackId,
        });
        return normalizeBrowserMediaTrackDescriptor(track);
      });

      return {
        streamId: request.streamId,
        tracks,
      };
    },
    stopMediaTrack(request) {
      const stream = streams.get(request?.streamId);
      if (!stream || typeof stream.getTracks !== 'function') {
        return;
      }
      const track = stream.getTracks().find((entry) => String(entry?.id || '') === request.trackId);
      if (!track) {
        return;
      }
      if (typeof track.stop === 'function') {
        track.stop();
      }
      dispatchHostCapabilityCustomEvent(eventTarget, 'media.mediaTrackEnded', {
        targetId: request.trackId,
        streamId: request.streamId,
        trackId: request.trackId,
      });
      if (stream.getTracks().every((entry) => entry.readyState === 'ended')) {
        streams.delete(request.streamId);
      }
    },
    createMediaRecorder(request) {
      const stream = streams.get(request?.streamId);
      if (!stream) {
        throw new Error(`Media stream ${String(request?.streamId || '')} is unavailable.`);
      }
      const requestedMimeType = String(request?.mimeType || '');
      const browserSupportsMimeType =
        BrowserMediaRecorder &&
        (!requestedMimeType ||
          typeof BrowserMediaRecorder.isTypeSupported !== 'function' ||
          BrowserMediaRecorder.isTypeSupported(requestedMimeType));
      const recorder = browserSupportsMimeType
        ? requestedMimeType
          ? new BrowserMediaRecorder(stream, { mimeType: requestedMimeType })
          : new BrowserMediaRecorder(stream)
        : new BrowserPcmMediaRecorder(stream, requestedMimeType);
      const session = {
        recorder,
        mimeType: String(recorder.mimeType || request.mimeType || ''),
        pendingStop: false,
        pendingData: Promise.resolve(),
      };
      recorders.set(request.recorderId, session);

      recorder.addEventListener('start', () => {
        dispatchHostCapabilityCustomEvent(eventTarget, 'media.mediaRecorderStarted', {
          targetId: request.recorderId,
          recorderId: request.recorderId,
        });
      });
      recorder.addEventListener('pause', () => {
        dispatchHostCapabilityCustomEvent(eventTarget, 'media.mediaRecorderPaused', {
          targetId: request.recorderId,
          recorderId: request.recorderId,
        });
      });
      recorder.addEventListener('resume', () => {
        dispatchHostCapabilityCustomEvent(eventTarget, 'media.mediaRecorderResumed', {
          targetId: request.recorderId,
          recorderId: request.recorderId,
        });
      });
      recorder.addEventListener('stop', async () => {
        await session.pendingData;
        dispatchHostCapabilityCustomEvent(eventTarget, 'media.mediaRecorderStopped', {
          targetId: request.recorderId,
          recorderId: request.recorderId,
          mimeType: session.mimeType,
        });
        recorders.delete(request.recorderId);
      });
      recorder.addEventListener('error', (event) => {
        const message = event?.error?.message || event?.message || 'Browser MediaRecorder failed.';
        dispatchHostCapabilityCustomEvent(eventTarget, 'media.mediaRecorderError', {
          targetId: request.recorderId,
          recorderId: request.recorderId,
          message,
        });
      });
      recorder.addEventListener('dataavailable', (event) => {
        session.pendingData = session.pendingData.then(async () => {
          const bytes = await blobToUint8Array(event?.data);
          const isLastChunk =
            typeof event?.inkIsLastChunk === 'boolean'
              ? event.inkIsLastChunk
              : Boolean(session.pendingStop);
          if (isLastChunk) {
            session.pendingStop = false;
          }
          dispatchHostCapabilityCustomEvent(eventTarget, 'media.mediaRecorderData', {
            targetId: request.recorderId,
            recorderId: request.recorderId,
            bytes,
            mimeType: String(event?.data?.type || session.mimeType || ''),
            isLastChunk,
          });
        });
      });

      return {
        recorderId: request.recorderId,
        mimeType: session.mimeType,
      };
    },
    startMediaRecorder(request) {
      const session = recorders.get(request?.recorderId);
      if (!session) {
        throw new Error(`MediaRecorder ${String(request?.recorderId || '')} is unavailable.`);
      }
      if (request?.timesliceMs != null) {
        session.recorder.start(request.timesliceMs);
      } else {
        session.recorder.start();
      }
    },
    pauseMediaRecorder(request) {
      const session = recorders.get(request?.recorderId);
      if (!session) {
        throw new Error(`MediaRecorder ${String(request?.recorderId || '')} is unavailable.`);
      }
      session.recorder.pause();
    },
    resumeMediaRecorder(request) {
      const session = recorders.get(request?.recorderId);
      if (!session) {
        throw new Error(`MediaRecorder ${String(request?.recorderId || '')} is unavailable.`);
      }
      session.recorder.resume();
    },
    requestMediaRecorderData(request) {
      const session = recorders.get(request?.recorderId);
      if (!session) {
        throw new Error(`MediaRecorder ${String(request?.recorderId || '')} is unavailable.`);
      }
      session.recorder.requestData();
    },
    stopMediaRecorder(request) {
      const session = recorders.get(request?.recorderId);
      if (!session) {
        return;
      }
      session.pendingStop = true;
      session.recorder.stop();
    },
  };
}

function normalizeHostCapabilities(capabilities, eventTarget = null) {
  const normalizedCapabilities = ensureHostCapabilitiesObject(capabilities);
  const defaultMediaCapability = createDefaultBrowserMediaCapability(eventTarget);
  if (!normalizedCapabilities && !defaultMediaCapability) {
    return null;
  }

  return {
    async handleRequest(capability, method, requestJson) {
      const serializer = HOST_CAPABILITY_SERIALIZERS[capability]?.[method];
      const hook =
        normalizedCapabilities?.[capability]?.[method] ||
        (capability === 'media' ? defaultMediaCapability?.[method] : undefined);
      if (capability === 'openService' && method === 'getVendorHeaders' && hook == null) {
        parseHostCapabilityRequest(requestJson, capability, method);
        return serializer({});
      }
      if (typeof serializer !== 'function' || typeof hook !== 'function') {
        throw new Error(
          `Host capability ${String(capability)}.${String(method)} is not configured on Web host`,
        );
      }
      const request = parseHostCapabilityRequest(requestJson, capability, method);
      const result = await hook(request);
      return serializer(result, request);
    },
  };
}

function createIpcEvent(type, target, detail) {
  return {
    type,
    target: target || null,
    bubbles: false,
    cancelable: false,
    default_prevented: false,
    propagation_stopped: false,
    detail,
  };
}

function requireDetailObject(detail, eventType) {
  if (detail == null || typeof detail !== 'object' || Array.isArray(detail)) {
    throw new TypeError(
      `Host capability event \`${eventType}\` requires an object detail payload.`,
    );
  }
  return detail;
}

function requireStringField(value, fieldName, eventType) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new TypeError(
      `Host capability event \`${eventType}\` requires a non-empty \`${fieldName}\`.`,
    );
  }
  return value.trim();
}

function buildSpeechSessionEvent(eventType, variant, detail) {
  const payload = requireDetailObject(detail, eventType);
  const targetId = requireStringField(payload.targetId, 'targetId', eventType);
  const sessionId = requireStringField(payload.sessionId, 'sessionId', eventType);
  return createIpcEvent(eventType, targetId, {
    type: 'Speech',
    data: {
      type: variant,
      data: { sessionId },
    },
  });
}

function buildSpeechResultEvent(eventType, detail) {
  const payload = requireDetailObject(detail, eventType);
  const targetId = requireStringField(payload.targetId, 'targetId', eventType);
  const sessionId = requireStringField(payload.sessionId, 'sessionId', eventType);
  const alternatives = Array.isArray(payload.alternatives)
    ? payload.alternatives.map((alternative) => ({
        transcript: String(alternative?.transcript || ''),
        confidence: Number(alternative?.confidence || 0),
      }))
    : [];
  return createIpcEvent(eventType, targetId, {
    type: 'Speech',
    data: {
      type: 'Result',
      data: {
        sessionId,
        resultIndex: Math.max(0, Math.trunc(Number(payload.resultIndex) || 0)),
        isFinal: Boolean(payload.isFinal),
        alternatives,
      },
    },
  });
}

function buildSpeechErrorEvent(eventType, detail) {
  const payload = requireDetailObject(detail, eventType);
  const targetId = requireStringField(payload.targetId, 'targetId', eventType);
  const sessionId = requireStringField(payload.sessionId, 'sessionId', eventType);
  return createIpcEvent(eventType, targetId, {
    type: 'Speech',
    data: {
      type: 'Error',
      data: {
        sessionId,
        error: String(payload.error || ''),
        message: String(payload.message || ''),
      },
    },
  });
}

function buildSpeechSynthesisEvent(eventType, variant, detail, createData) {
  const payload = requireDetailObject(detail, eventType);
  const synthesisId = requireStringField(payload.synthesisId, 'synthesisId', eventType);
  return createIpcEvent(eventType, synthesisId, {
    type: 'Speech',
    data: {
      type: variant,
      data: createData(payload, synthesisId),
    },
  });
}

function normalizeSpeechSynthesisCue(cue) {
  return {
    id: typeof cue?.id === 'string' ? cue.id : '',
    text: String(cue?.text || ''),
    startTimeMs: Math.max(0, Math.trunc(Number(cue?.startTimeMs) || 0)),
    endTimeMs: Math.max(0, Math.trunc(Number(cue?.endTimeMs) || 0)),
    charIndex: cue?.charIndex == null ? null : Math.max(0, Math.trunc(Number(cue.charIndex) || 0)),
    charLength:
      cue?.charLength == null ? null : Math.max(0, Math.trunc(Number(cue.charLength) || 0)),
  };
}

function buildGeolocationPositionEvent(eventType, detail) {
  const payload = requireDetailObject(detail, eventType);
  const targetId = requireStringField(payload.targetId, 'targetId', eventType);
  const watchId = Math.trunc(Number(payload.watchId) || 0);
  return createIpcEvent(eventType, targetId, {
    type: 'Geolocation',
    data: {
      type: 'Position',
      data: {
        watchId,
        position: {
          coords: {
            latitude: normalizeFiniteNumber(payload.coords?.latitude, 0),
            longitude: normalizeFiniteNumber(payload.coords?.longitude, 0),
            accuracy: normalizeFiniteNumber(payload.coords?.accuracy, 0),
            altitude: normalizeOptionalFiniteNumber(payload.coords?.altitude),
            altitudeAccuracy: normalizeOptionalFiniteNumber(payload.coords?.altitudeAccuracy),
            heading: normalizeOptionalFiniteNumber(payload.coords?.heading),
            speed: normalizeOptionalFiniteNumber(payload.coords?.speed),
          },
          timestamp: normalizeFiniteNumber(payload.timestamp, 0),
        },
      },
    },
  });
}

function buildGeolocationErrorEvent(eventType, detail) {
  const payload = requireDetailObject(detail, eventType);
  const targetId = requireStringField(payload.targetId, 'targetId', eventType);
  const watchId = Math.trunc(Number(payload.watchId) || 0);
  return createIpcEvent(eventType, targetId, {
    type: 'Geolocation',
    data: {
      type: 'Error',
      data: {
        watchId,
        code: Math.max(1, Math.min(3, Math.trunc(Number(payload.code) || 0))),
        message: String(payload.message || ''),
      },
    },
  });
}

function buildSensorSessionEvent(category, eventType, variant, detail) {
  const payload = requireDetailObject(detail, eventType);
  const targetId = requireStringField(payload.targetId, 'targetId', eventType);
  const sessionId = requireStringField(payload.sessionId, 'sessionId', eventType);
  return createIpcEvent(eventType, targetId, {
    type: category,
    data: {
      type: variant,
      data: { sessionId },
    },
  });
}

function buildSensorReadingEvent(category, eventType, detail, valueFields) {
  const payload = requireDetailObject(detail, eventType);
  const targetId = requireStringField(payload.targetId, 'targetId', eventType);
  const sessionId = requireStringField(payload.sessionId, 'sessionId', eventType);
  const data = {
    sessionId,
    timestamp: Number(payload.timestamp || 0),
  };
  for (const fieldName of valueFields) {
    data[fieldName] = Number(payload[fieldName] || 0);
  }
  return createIpcEvent(eventType, targetId, {
    type: category,
    data: {
      type: 'Reading',
      data,
    },
  });
}

function buildSensorErrorEvent(category, eventType, detail) {
  const payload = requireDetailObject(detail, eventType);
  const targetId = requireStringField(payload.targetId, 'targetId', eventType);
  const sessionId = requireStringField(payload.sessionId, 'sessionId', eventType);
  return createIpcEvent(eventType, targetId, {
    type: category,
    data: {
      type: 'Error',
      data: {
        sessionId,
        error: String(payload.error || ''),
        message: String(payload.message || ''),
      },
    },
  });
}

function buildBatteryStatusChangeEvent(eventType, detail) {
  const payload = requireDetailObject(detail, eventType);
  const targetId = requireStringField(payload.targetId, 'targetId', eventType);
  const status = normalizeBatteryStatus(payload, `host event \`${eventType}\``);
  return createIpcEvent(eventType, targetId, {
    type: 'Battery',
    data: {
      type: 'StatusChange',
      data: {
        charging: status.charging,
        chargingTime: status.chargingTime,
        dischargingTime: status.dischargingTime,
        level: status.level,
      },
    },
  });
}

function buildMediaRecordingEvent(eventType, variant, detail, data) {
  const payload = requireDetailObject(detail, eventType);
  const targetId = requireStringField(payload.targetId, 'targetId', eventType);
  return createIpcEvent(eventType, targetId, {
    type: 'Media',
    data:
      data == null
        ? { type: variant }
        : {
            type: variant,
            data,
          },
  });
}

const HOST_CAPABILITY_EVENT_BUILDERS = {
  'speech.synthesisChunk': (detail) =>
    buildSpeechSynthesisEvent('speech.synthesisChunk', 'SynthesisChunk', detail, (payload, id) => {
      const audio = cloneUint8Array(payload.audio);
      if (!audio) {
        throw new TypeError(
          'Host capability event `speech.synthesisChunk` requires binary `audio`.',
        );
      }
      return {
        synthesisId: id,
        sequence: Math.max(0, Math.trunc(Number(payload.sequence) || 0)),
        audio: Array.from(audio),
        cues: Array.isArray(payload.cues) ? payload.cues.map(normalizeSpeechSynthesisCue) : [],
      };
    }),
  'speech.synthesisEnd': (detail) =>
    buildSpeechSynthesisEvent('speech.synthesisEnd', 'SynthesisEnd', detail, (payload, id) => ({
      synthesisId: id,
      durationMs: Math.max(0, Math.trunc(Number(payload.durationMs) || 0)),
    })),
  'speech.synthesisError': (detail) =>
    buildSpeechSynthesisEvent('speech.synthesisError', 'SynthesisError', detail, (payload, id) => ({
      synthesisId: id,
      error: String(payload.error || ''),
      message: String(payload.message || ''),
    })),
  'speech.synthesisAborted': (detail) =>
    buildSpeechSynthesisEvent(
      'speech.synthesisAborted',
      'SynthesisAborted',
      detail,
      (_payload, id) => ({ synthesisId: id }),
    ),
  'speech.start': (detail) => buildSpeechSessionEvent('speech.start', 'Start', detail),
  'speech.audiostart': (detail) =>
    buildSpeechSessionEvent('speech.audiostart', 'AudioStart', detail),
  'speech.soundstart': (detail) =>
    buildSpeechSessionEvent('speech.soundstart', 'SoundStart', detail),
  'speech.speechstart': (detail) =>
    buildSpeechSessionEvent('speech.speechstart', 'SpeechStart', detail),
  'speech.result': (detail) => buildSpeechResultEvent('speech.result', detail),
  'speech.nomatch': (detail) => buildSpeechSessionEvent('speech.nomatch', 'NoMatch', detail),
  'speech.error': (detail) => buildSpeechErrorEvent('speech.error', detail),
  'speech.speechend': (detail) => buildSpeechSessionEvent('speech.speechend', 'SpeechEnd', detail),
  'speech.soundend': (detail) => buildSpeechSessionEvent('speech.soundend', 'SoundEnd', detail),
  'speech.audioend': (detail) => buildSpeechSessionEvent('speech.audioend', 'AudioEnd', detail),
  'speech.end': (detail) => buildSpeechSessionEvent('speech.end', 'End', detail),
  'geolocation.position': (detail) => buildGeolocationPositionEvent('geolocation.position', detail),
  'geolocation.error': (detail) => buildGeolocationErrorEvent('geolocation.error', detail),
  'battery.statuschange': (detail) => buildBatteryStatusChangeEvent('battery.statuschange', detail),
  'absoluteOrientation.activate': (detail) =>
    buildSensorSessionEvent(
      'AbsoluteOrientation',
      'absoluteOrientation.activate',
      'Activate',
      detail,
    ),
  'absoluteOrientation.reading': (detail) =>
    buildSensorReadingEvent('AbsoluteOrientation', 'absoluteOrientation.reading', detail, [
      'x',
      'y',
      'z',
      'w',
    ]),
  'absoluteOrientation.error': (detail) =>
    buildSensorErrorEvent('AbsoluteOrientation', 'absoluteOrientation.error', detail),
  'accelerometer.activate': (detail) =>
    buildSensorSessionEvent('Accelerometer', 'accelerometer.activate', 'Activate', detail),
  'accelerometer.reading': (detail) =>
    buildSensorReadingEvent('Accelerometer', 'accelerometer.reading', detail, ['x', 'y', 'z']),
  'accelerometer.error': (detail) =>
    buildSensorErrorEvent('Accelerometer', 'accelerometer.error', detail),
  'gyroscope.activate': (detail) =>
    buildSensorSessionEvent('Gyroscope', 'gyroscope.activate', 'Activate', detail),
  'gyroscope.reading': (detail) =>
    buildSensorReadingEvent('Gyroscope', 'gyroscope.reading', detail, ['x', 'y', 'z']),
  'gyroscope.error': (detail) => buildSensorErrorEvent('Gyroscope', 'gyroscope.error', detail),
  'magnetometer.activate': (detail) =>
    buildSensorSessionEvent('Magnetometer', 'magnetometer.activate', 'Activate', detail),
  'magnetometer.reading': (detail) =>
    buildSensorReadingEvent('Magnetometer', 'magnetometer.reading', detail, ['x', 'y', 'z']),
  'magnetometer.error': (detail) =>
    buildSensorErrorEvent('Magnetometer', 'magnetometer.error', detail),
  'media.mediaTrackStarted': (detail) => {
    const payload = requireDetailObject(detail, 'media.mediaTrackStarted');
    return buildMediaRecordingEvent('media', 'MediaTrackStarted', detail, {
      streamId: String(payload.streamId || ''),
      trackId: String(payload.trackId || ''),
    });
  },
  'media.mediaTrackEnded': (detail) => {
    const payload = requireDetailObject(detail, 'media.mediaTrackEnded');
    return buildMediaRecordingEvent('media', 'MediaTrackEnded', detail, {
      streamId: String(payload.streamId || ''),
      trackId: String(payload.trackId || ''),
    });
  },
  'media.mediaTrackMuted': (detail) => {
    const payload = requireDetailObject(detail, 'media.mediaTrackMuted');
    return buildMediaRecordingEvent('media', 'MediaTrackMuted', detail, {
      streamId: String(payload.streamId || ''),
      trackId: String(payload.trackId || ''),
    });
  },
  'media.mediaTrackUnmuted': (detail) => {
    const payload = requireDetailObject(detail, 'media.mediaTrackUnmuted');
    return buildMediaRecordingEvent('media', 'MediaTrackUnmuted', detail, {
      streamId: String(payload.streamId || ''),
      trackId: String(payload.trackId || ''),
    });
  },
  'media.mediaTrackError': (detail) => {
    const payload = requireDetailObject(detail, 'media.mediaTrackError');
    return buildMediaRecordingEvent('media', 'MediaTrackError', detail, {
      streamId: String(payload.streamId || ''),
      trackId: String(payload.trackId || ''),
      message: String(payload.message || ''),
    });
  },
  'media.mediaRecorderStarted': (detail) => {
    const payload = requireDetailObject(detail, 'media.mediaRecorderStarted');
    return buildMediaRecordingEvent('media', 'MediaRecorderStarted', detail, {
      recorderId: String(payload.recorderId || ''),
    });
  },
  'media.mediaRecorderData': (detail) => {
    const payload = requireDetailObject(detail, 'media.mediaRecorderData');
    const bytes = cloneUint8Array(payload.bytes ?? payload.data ?? payload.buffer);
    if (!bytes) {
      throw new TypeError(
        'Host capability event `media.mediaRecorderData` requires binary `bytes`.',
      );
    }
    return buildMediaRecordingEvent('media', 'MediaRecorderData', detail, {
      recorderId: String(payload.recorderId || ''),
      bytes: Array.from(bytes),
      mimeType: String(payload.mimeType || ''),
      isLastChunk: Boolean(payload.isLastChunk),
    });
  },
  'media.mediaRecorderPaused': (detail) => {
    const payload = requireDetailObject(detail, 'media.mediaRecorderPaused');
    return buildMediaRecordingEvent('media', 'MediaRecorderPaused', detail, {
      recorderId: String(payload.recorderId || ''),
    });
  },
  'media.mediaRecorderResumed': (detail) => {
    const payload = requireDetailObject(detail, 'media.mediaRecorderResumed');
    return buildMediaRecordingEvent('media', 'MediaRecorderResumed', detail, {
      recorderId: String(payload.recorderId || ''),
    });
  },
  'media.mediaRecorderStopped': (detail) => {
    const payload = requireDetailObject(detail, 'media.mediaRecorderStopped');
    return buildMediaRecordingEvent('media', 'MediaRecorderStopped', detail, {
      recorderId: String(payload.recorderId || ''),
      mimeType: payload.mimeType == null ? null : String(payload.mimeType),
    });
  },
  'media.mediaRecorderError': (detail) => {
    const payload = requireDetailObject(detail, 'media.mediaRecorderError');
    return buildMediaRecordingEvent('media', 'MediaRecorderError', detail, {
      recorderId: String(payload.recorderId || ''),
      message: String(payload.message || ''),
    });
  },
  'media.mediaDevicesChanged': (detail) => {
    const payload = requireDetailObject(detail, 'media.mediaDevicesChanged');
    return buildMediaRecordingEvent('media', 'MediaDevicesChanged', detail, {
      devices: Array.isArray(payload.devices)
        ? payload.devices.map((device) => ({
            deviceId: String(device?.deviceId || ''),
            kind: String(device?.kind || ''),
            label: String(device?.label || ''),
            groupId: String(device?.groupId || ''),
          }))
        : [],
    });
  },
};

const HOST_CAPABILITY_EVENT_TYPES = Object.keys(HOST_CAPABILITY_EVENT_BUILDERS);

class InkHostMessageStream {
  #rawStream;
  #requestRender;
  #closed;

  constructor(rawStream, requestRender) {
    this.#rawStream = rawStream;
    this.#requestRender = requestRender;
    this.#closed = false;
  }

  write(chunk) {
    if (typeof chunk !== 'string') {
      throw new TypeError('`chunk` must be a string.');
    }
    this.#rawStream.write(chunk);
    this.#requestRender();
  }

  close() {
    if (this.#closed) {
      return;
    }
    this.#rawStream.close();
    this.#closed = true;
    this.#requestRender();
  }
}

export async function initInk(options = {}) {
  if (options.requestInterceptor) {
    setInkRequestInterceptor(options.requestInterceptor);
  } else if (options.proxyUrl) {
    setInkRequestInterceptor(createProxyUrlResolver(options));
  }

  if (!bindingsPromise) {
    const initInput =
      options.wasmUrl || options.moduleOrPath || new URL('./pkg/ink_web_bg.wasm', import.meta.url);
    bindingsPromise = initInkWasm(initInput).then(() => ({
      InkWebView: RawInkWebView,
      getInkBundleVersion: getInkBundleVersionFromWasm,
      getInkVersion: getInkVersionFromWasm,
      satisfiesInkEngine: satisfiesInkEngineFromWasm,
    }));
  }

  return bindingsPromise;
}

export function getInkVersion() {
  return getInkVersionFromWasm();
}

export function getInkBundleVersion() {
  return getInkBundleVersionFromWasm();
}

/**
 * Returns whether the Ink runtime version satisfies the supplied AIX
 * engine range.
 *
 * The engine range lets a package declare which Ink runtime versions it is
 * compatible with. Hosts can use this helper to preflight compatibility before
 * calling `open()` or `openBundle()`.
 *
 * Common range examples include an exact version like `0.17.0`, a caret range
 * like `^0.17.0`, a tilde range like `~0.17.0`, or comparator ranges such as
 * `>=0.17.0 <0.18.0`.
 *
 * Example:
 * ```js
 * const engineRange = packageManifest.engine;
 * if (!satisfiesInkEngine(engineRange)) {
 *   console.warn('Skip incompatible package:', engineRange);
 *   return;
 * }
 *
 * await inkView.open(aixPath);
 * ```
 */
export function satisfiesInkEngine(range) {
  if (typeof range !== 'string') {
    throw new TypeError('`range` must be a string.');
  }
  return satisfiesInkEngineFromWasm(range);
}

export class InkView {
  static async create(options) {
    const config = options || {};
    const width = Number(config.width);
    const layoutMode = normalizeLayoutMode(config.layoutMode);
    const themeName = normalizeThemeName(config.themeName);
    const themeCss = normalizeThemeCss(config.themeCss);
    const height = Number(config.height);
    const scaleFactor = normalizeScaleFactor(config.scaleFactor);
    const appFps = normalizeAppFps(config.appFps);
    if (!Number.isFinite(width)) {
      throw new Error('`width` is a required numeric value.');
    }
    if (layoutMode === 'bounded' && !Number.isFinite(height)) {
      throw new Error('`height` is required when `layoutMode` is `bounded`.');
    }
    const initialHeight =
      layoutMode === 'width-constrained-auto-height' && !Number.isFinite(height) ? 1 : height;

    const bindings = await initInk(config.wasm);
    const physicalWidth = Math.max(1, Math.round(width * scaleFactor));
    const initialHeightRounder =
      layoutMode === 'width-constrained-auto-height' ? Math.ceil : Math.round;
    const physicalHeight = Math.max(1, initialHeightRounder(initialHeight * scaleFactor));
    const rawView = new bindings.InkWebView(
      physicalWidth,
      physicalHeight,
      scaleFactor,
      appFps,
      layoutMode,
      themeName,
      themeCss,
      Boolean(config.systemAgent),
      configuredNavigatorHost,
      normalizeHostPackages(config.hostPackages),
      normalizeHostFontsDir(config.hostFontsDir),
    );
    const view = new InkView(rawView, config);
    const initialSurface =
      config.surface || (config.canvas ? { type: 'html-canvas', canvas: config.canvas } : null);
    if (initialSurface) {
      view.attachSurface(initialSurface);
    }
    if (config.autoRender) {
      view.startRendering();
    }

    return view;
  }

  #rawView;
  #canvas;
  #surface;
  #animationFrameApi;
  #frameHandle;
  #autoRender;
  #closeRequested;
  #destroyed;
  #domCleanup;
  #onCloseRequested;
  #onClosed;
  #clearOnDestroy;
  #logicalWidth;
  #logicalHeight;
  #boundedHeight;
  #layoutMode;
  #latestContentSize;
  #pendingAutoHeightContentSize;
  #resizeSnapshot;
  #scaleFactor;
  #surfaceBinding;
  #onContentSizeChanged;
  #onMessage;
  #onPerformanceEntry;
  #hostCapabilitiesTarget;

  constructor(rawView, options = {}) {
    this.#rawView = rawView;
    this.#canvas = options.canvas || null;
    this.#surface = null;
    this.#animationFrameApi = ensureAnimationFrameApi();
    this.#frameHandle = null;
    this.#autoRender = false;
    this.#closeRequested = false;
    this.#destroyed = false;
    this.#domCleanup = null;
    this.#onCloseRequested = null;
    this.#onClosed = null;
    this.#clearOnDestroy = options.clearOnDestroy !== false;
    this.#logicalWidth = Number(options.width) || 1;
    this.#logicalHeight = Number(options.height) || 1;
    this.#boundedHeight = Number(options.height) || 1;
    this.#layoutMode = normalizeLayoutMode(options.layoutMode);
    this.#latestContentSize = null;
    this.#pendingAutoHeightContentSize = null;
    this.#resizeSnapshot = null;
    this.#scaleFactor = normalizeScaleFactor(options.scaleFactor);
    this.#surfaceBinding = null;
    this.#onContentSizeChanged = null;
    this.#onMessage = null;
    this.#onPerformanceEntry = null;
    this.#hostCapabilitiesTarget = new EventTarget();
    this.#bindHostCapabilityEvents();
    this.onContentSizeChanged = options.onContentSizeChanged || null;
    this.onMessage = options.onMessage || null;
    this.onPerformanceEntry = options.onPerformanceEntry || null;
    this.setHostCapabilities(options.hostCapabilities || null);
  }

  #clearSurfaceContents() {
    const clearTarget = (target) => {
      if (!target || typeof target.getContext !== 'function') {
        return;
      }

      const context = target.getContext('2d');
      if (!context || typeof context.clearRect !== 'function') {
        return;
      }

      if (typeof context.save === 'function') {
        context.save();
      }
      if (typeof context.setTransform === 'function') {
        context.setTransform(1, 0, 0, 1, 0, 0);
      } else if (typeof context.resetTransform === 'function') {
        context.resetTransform();
      }
      context.clearRect(0, 0, Number(target.width) || 0, Number(target.height) || 0);
      if (typeof context.restore === 'function') {
        context.restore();
      }
    };

    clearTarget(this.#canvas);
    if (this.#surfaceBinding?.type === 'offscreen-canvas') {
      clearTarget(this.#surfaceBinding.targetCanvas);
    }
  }

  #syncSurfaceBindingSize(width, height, options = {}) {
    if (!this.#surfaceBinding || this.#surfaceBinding.type !== 'offscreen-canvas') {
      return false;
    }

    const nextWidth = Math.max(1, Number(width) || 1);
    const nextHeight = Math.max(1, Number(height) || 1);
    const nextBitmapWidth = Math.max(1, Math.round(nextWidth * this.#scaleFactor));
    const heightRounder = options.ceilHeight ? Math.ceil : Math.round;
    const nextBitmapHeight = Math.max(1, heightRounder(nextHeight * this.#scaleFactor));
    const { backingCanvas, targetCanvas } = this.#surfaceBinding;
    const changed =
      Number(backingCanvas.width) !== nextBitmapWidth ||
      Number(backingCanvas.height) !== nextBitmapHeight ||
      Number(targetCanvas.width) !== nextBitmapWidth ||
      Number(targetCanvas.height) !== nextBitmapHeight;
    if (!changed) {
      return false;
    }

    this.#captureResizeSnapshot();
    backingCanvas.width = nextBitmapWidth;
    backingCanvas.height = nextBitmapHeight;
    targetCanvas.width = nextBitmapWidth;
    targetCanvas.height = nextBitmapHeight;
    return true;
  }

  #syncHtmlCanvasBindingSize(width, height, options = {}) {
    if (!this.#surfaceBinding || this.#surfaceBinding.type !== 'html-canvas') {
      return false;
    }

    const nextWidth = Math.max(1, Number(width) || 1);
    const nextHeight = Math.max(1, Number(height) || 1);
    const nextBitmapWidth = Math.max(1, Math.round(nextWidth * this.#scaleFactor));
    const heightRounder = options.ceilHeight ? Math.ceil : Math.round;
    const nextBitmapHeight = Math.max(1, heightRounder(nextHeight * this.#scaleFactor));
    const canvas = this.#surfaceBinding.canvas;
    let changed = false;

    if (Number(canvas.width) !== nextBitmapWidth) {
      canvas.width = nextBitmapWidth;
      changed = true;
    }
    if (Number(canvas.height) !== nextBitmapHeight) {
      canvas.height = nextBitmapHeight;
      changed = true;
    }

    if (options.updateStyleWidth && canvas.style?.width !== `${nextWidth}px`) {
      canvas.style.width = `${nextWidth}px`;
      changed = true;
    }

    if (options.updateStyleHeight && canvas.style?.height !== `${nextHeight}px`) {
      canvas.style.height = `${nextHeight}px`;
      changed = true;
    }

    if (options.updateContainerWidth && canvas.parentElement?.style?.width !== `${nextWidth}px`) {
      canvas.parentElement.style.width = `${nextWidth}px`;
      changed = true;
    }

    if (options.updateContainerHeight && canvas.parentElement?.style?.height !== 'auto') {
      canvas.parentElement.style.height = 'auto';
      changed = true;
    }

    return changed;
  }

  #syncHtmlCanvasAutoHeight(contentSize) {
    if (this.#layoutMode !== 'width-constrained-auto-height') {
      return false;
    }
    if (!this.#surfaceBinding) {
      return false;
    }

    const nextHeight = Math.max(1, Number(contentSize?.height) || 1);
    let adopted = false;
    if (this.#surfaceBinding.type === 'html-canvas') {
      adopted = this.#syncHtmlCanvasBindingSize(this.#logicalWidth, nextHeight, {
        ceilHeight: true,
        updateStyleWidth: true,
        updateStyleHeight: true,
        updateContainerWidth: true,
        updateContainerHeight: true,
      });
    } else {
      if (this.#logicalHeight === nextHeight) {
        return false;
      }
      this.#syncSurfaceBindingSize(this.#logicalWidth, nextHeight, { ceilHeight: true });
      adopted = true;
    }

    if (!adopted && this.#logicalHeight === nextHeight) {
      return false;
    }

    this.#logicalHeight = nextHeight;
    this.#syncRawViewSize(this.#logicalWidth, nextHeight, false, { ceilHeight: true });
    return true;
  }

  #deferAutoHeightContentSize(contentSize) {
    if (this.#layoutMode !== 'width-constrained-auto-height') {
      return false;
    }
    this.#pendingAutoHeightContentSize = contentSize;
    return true;
  }

  #applyPendingAutoHeightContentSize() {
    const contentSize = this.#pendingAutoHeightContentSize;
    if (!contentSize) {
      return false;
    }
    this.#pendingAutoHeightContentSize = null;
    return this.#syncHtmlCanvasAutoHeight(contentSize);
  }

  #syncBoundSurfaceSize() {
    if (this.#surfaceBinding?.type === 'html-canvas') {
      this.#syncHtmlCanvasBindingSize(this.#logicalWidth, this.#logicalHeight, {
        updateStyleWidth: true,
        updateContainerWidth: true,
      });
      return;
    }
    this.#syncSurfaceBindingSize(this.#logicalWidth, this.#logicalHeight);
  }

  #syncAutoHeightSurfaceSize() {
    if (this.#surfaceBinding?.type === 'html-canvas') {
      this.#syncHtmlCanvasBindingSize(this.#logicalWidth, this.#logicalHeight, {
        ceilHeight: true,
        updateStyleWidth: true,
        updateStyleHeight: true,
        updateContainerWidth: true,
        updateContainerHeight: true,
      });
      return;
    }
    this.#syncSurfaceBindingSize(this.#logicalWidth, this.#logicalHeight, { ceilHeight: true });
  }

  #syncSurfaceForCurrentLayoutMode() {
    if (this.#layoutMode === 'width-constrained-auto-height') {
      this.#syncAutoHeightSurfaceSize();
      return;
    }
    this.#syncBoundSurfaceSize();
  }

  #physicalWidth() {
    return Math.max(1, Math.round(this.#logicalWidth * this.#scaleFactor));
  }

  #physicalHeight(height = this.#logicalHeight) {
    const heightRounder =
      this.#layoutMode === 'width-constrained-auto-height' ? Math.ceil : Math.round;
    return Math.max(1, heightRounder((Number(height) || 1) * this.#scaleFactor));
  }

  #syncRawViewSize(
    width = this.#logicalWidth,
    height = this.#logicalHeight,
    resetScroll = false,
    options = {},
  ) {
    if (typeof this.#rawView.setViewport !== 'function') {
      return;
    }
    const physicalWidth = Math.max(1, Math.round((Number(width) || 1) * this.#scaleFactor));
    const heightRounder =
      options.ceilHeight || this.#layoutMode === 'width-constrained-auto-height'
        ? Math.ceil
        : Math.round;
    const physicalHeight = Math.max(1, heightRounder((Number(height) || 1) * this.#scaleFactor));
    if (this.#surfaceBinding?.type === 'offscreen-canvas') {
      const { backingCanvas } = this.#surfaceBinding;
      if (
        Number(backingCanvas.width) !== physicalWidth ||
        Number(backingCanvas.height) !== physicalHeight
      ) {
        this.#captureResizeSnapshot();
      }
    }
    if (resetScroll) {
      this.#rawView.setViewport(physicalWidth, physicalHeight, this.#scaleFactor, true);
      return;
    }
    this.#rawView.setViewport(physicalWidth, physicalHeight, this.#scaleFactor);
  }

  #currentContentSize() {
    if (
      typeof this.#rawView.currentContentWidth !== 'function' ||
      typeof this.#rawView.currentContentHeight !== 'function'
    ) {
      return null;
    }
    const width = Number(this.#rawView.currentContentWidth());
    const height = Number(this.#rawView.currentContentHeight());
    if (!Number.isFinite(width) || !Number.isFinite(height) || width < 0 || height < 0) {
      return null;
    }
    return { width, height };
  }

  #consumeContentSizeChanged() {
    if (typeof this.#rawView.consumeContentSizeChanged !== 'function') {
      return false;
    }
    if (!this.#rawView.consumeContentSizeChanged()) {
      return false;
    }

    const contentSize = this.#currentContentSize();
    if (!contentSize) {
      return false;
    }
    this.#latestContentSize = contentSize;
    this.#deferAutoHeightContentSize(contentSize);
    if (typeof this.#onContentSizeChanged === 'function') {
      this.#onContentSizeChanged(contentSize);
    }
    return true;
  }

  #currentMessageEvent() {
    if (typeof this.#rawView.currentMessageEventJson !== 'function') {
      return null;
    }
    const json = this.#rawView.currentMessageEventJson();
    if (!json) {
      return null;
    }
    try {
      const event = JSON.parse(json);
      if (!event || typeof event !== 'object') {
        return null;
      }
      return event;
    } catch {
      return null;
    }
  }

  #consumeMessageEvent() {
    if (typeof this.#rawView.consumeMessageEvent !== 'function') {
      return false;
    }
    let consumed = false;
    while (this.#rawView.consumeMessageEvent()) {
      const event = this.#currentMessageEvent();
      if (!event) {
        continue;
      }
      consumed = true;
      if (typeof this.#onMessage === 'function') {
        this.#onMessage(event);
      }
    }
    return consumed;
  }

  #currentPerformanceEntry() {
    if (typeof this.#rawView.currentPerformanceEntryJson !== 'function') {
      return null;
    }
    const json = this.#rawView.currentPerformanceEntryJson();
    if (!json) {
      return null;
    }
    try {
      const entry = JSON.parse(json);
      if (!entry || typeof entry !== 'object') {
        return null;
      }
      return entry;
    } catch {
      return null;
    }
  }

  #consumePerformanceEntry() {
    if (typeof this.#rawView.consumePerformanceEntry !== 'function') {
      return false;
    }
    let consumed = false;
    while (this.#rawView.consumePerformanceEntry()) {
      const entry = this.#currentPerformanceEntry();
      if (!entry) {
        continue;
      }
      consumed = true;
      if (typeof this.#onPerformanceEntry === 'function') {
        this.#onPerformanceEntry(entry);
      }
    }
    return consumed;
  }

  #captureResizeSnapshot() {
    if (
      this.#resizeSnapshot ||
      !this.#surfaceBinding ||
      this.#surfaceBinding.type !== 'offscreen-canvas'
    ) {
      return false;
    }

    const { targetCanvas, backingCanvas } = this.#surfaceBinding;
    const sourceCanvas = targetCanvas || backingCanvas;
    const sourceWidth = Math.max(0, Number(sourceCanvas?.width) || 0);
    const sourceHeight = Math.max(0, Number(sourceCanvas?.height) || 0);
    if (sourceWidth <= 0 || sourceHeight <= 0) {
      return false;
    }

    let snapshotCanvas;
    try {
      snapshotCanvas = createDomCanvas(sourceWidth, sourceHeight);
    } catch {
      return false;
    }

    const context = snapshotCanvas.getContext?.('2d');
    if (!context || typeof context.drawImage !== 'function') {
      return false;
    }

    try {
      context.drawImage(sourceCanvas, 0, 0);
    } catch {
      try {
        context.drawImage(backingCanvas, 0, 0);
      } catch {
        return false;
      }
    }

    this.#resizeSnapshot = {
      canvas: snapshotCanvas,
      width: sourceWidth,
      height: sourceHeight,
    };
    return true;
  }

  #clearResizeSnapshot() {
    this.#resizeSnapshot = null;
  }

  #presentSurface(options = {}) {
    if (!this.#surfaceBinding || this.#surfaceBinding.type !== 'offscreen-canvas') {
      return;
    }

    const { backingCanvas, targetCanvas } = this.#surfaceBinding;
    const snapshot = options.useResizeSnapshot ? this.#resizeSnapshot : null;
    const sourceCanvas = snapshot?.canvas || backingCanvas;
    const sourceWidth = Math.max(0, Number(snapshot?.width ?? sourceCanvas.width) || 0);
    const sourceHeight = Math.max(0, Number(snapshot?.height ?? sourceCanvas.height) || 0);
    const targetWidth = Math.max(0, Number(targetCanvas.width) || 0);
    const targetHeight = Math.max(0, Number(targetCanvas.height) || 0);
    const drawWidth = Math.min(sourceWidth, targetWidth);
    const drawHeight = Math.min(sourceHeight, targetHeight);
    const context = targetCanvas.getContext('2d');
    if (
      !context ||
      typeof context.clearRect !== 'function' ||
      typeof context.drawImage !== 'function'
    ) {
      return;
    }

    if (typeof context.save === 'function') {
      context.save();
    }
    if (typeof context.setTransform === 'function') {
      context.setTransform(1, 0, 0, 1, 0, 0);
    } else if (typeof context.resetTransform === 'function') {
      context.resetTransform();
    }
    context.clearRect(0, 0, targetWidth, targetHeight);
    if (drawWidth > 0 && drawHeight > 0) {
      context.drawImage(sourceCanvas, 0, 0, drawWidth, drawHeight, 0, 0, drawWidth, drawHeight);
    }
    if (typeof context.restore === 'function') {
      context.restore();
    }
  }

  #resetCloseRequestedState() {
    this.#closeRequested = false;
  }

  #consumeCloseRequested() {
    if (this.#destroyed || this.#closeRequested) {
      return false;
    }
    if (typeof this.#rawView.consumeCloseRequested !== 'function') {
      return false;
    }
    if (!this.#rawView.consumeCloseRequested()) {
      return false;
    }

    const source =
      typeof this.#rawView.currentCloseRequestSource === 'function'
        ? this.#rawView.currentCloseRequestSource()
        : '';
    const context = { source };
    let allow = true;
    if (typeof this.#onCloseRequested === 'function') {
      try {
        const result = this.#onCloseRequested(context);
        if (typeof result !== 'boolean') {
          console.warn(
            'InkView onCloseRequested returned a non-boolean value; treating it as allow=true.',
            result,
          );
        } else {
          allow = result;
        }
      } catch (error) {
        allow = false;
        console.error('InkView onCloseRequested failed; denying close request.', error);
      }
    }
    if (typeof this.#rawView.resolveCloseRequested === 'function') {
      this.#rawView.resolveCloseRequested(allow);
    }
    if (allow) {
      this.#consumeClosed();
    }
    return false;
  }

  #consumeClosed() {
    if (this.#destroyed || this.#closeRequested) {
      return this.#closeRequested;
    }
    if (typeof this.#rawView.consumeClosed !== 'function') {
      return false;
    }
    if (!this.#rawView.consumeClosed()) {
      return false;
    }

    const source =
      typeof this.#rawView.currentClosedSource === 'function'
        ? this.#rawView.currentClosedSource()
        : '';
    this.#closeRequested = true;
    this.stopRendering();
    if (typeof this.#onClosed === 'function') {
      this.#onClosed({ source });
    }
    return true;
  }

  #bindHostCapabilityEvents() {
    for (const eventType of HOST_CAPABILITY_EVENT_TYPES) {
      this.#hostCapabilitiesTarget.addEventListener(eventType, (event) => {
        if (this.#destroyed || typeof this.#rawView.dispatchHostCapabilityEvent !== 'function') {
          return;
        }
        const buildIpcEvent = HOST_CAPABILITY_EVENT_BUILDERS[eventType];
        if (typeof buildIpcEvent !== 'function') {
          return;
        }
        try {
          const ipcEvent = buildIpcEvent(event?.detail);
          this.#rawView.dispatchHostCapabilityEvent(JSON.stringify(ipcEvent));
          this.requestRender();
        } catch (error) {
          console.error(`InkView failed to bridge host capability event \`${eventType}\`.`, error);
        }
      });
    }
  }

  attachSurface(surface) {
    const normalizedSurface = normalizeSurfaceDescriptor(surface);
    if (!normalizedSurface) {
      throw new TypeError('`surface` must be provided.');
    }
    this.#clearResizeSnapshot();

    if (normalizedSurface.type === 'html-canvas') {
      this.#rawView.bindCanvas(normalizedSurface.canvas);
      this.#surfaceBinding = {
        type: 'html-canvas',
        canvas: normalizedSurface.canvas,
      };
      this.#surface = normalizedSurface;
      this.#canvas = normalizedSurface.canvas;
      this.#syncRawViewSize();
      if (this.#layoutMode === 'width-constrained-auto-height') {
        this.#syncHtmlCanvasAutoHeight(this.#latestContentSize || { height: this.#logicalHeight });
      }
      return this;
    }

    const backingCanvas = createDomCanvas(this.#physicalWidth(), this.#physicalHeight());
    this.#rawView.bindCanvas(backingCanvas);
    this.#surfaceBinding = {
      type: 'offscreen-canvas',
      backingCanvas,
      targetCanvas: normalizedSurface.canvas,
    };
    this.#surface = normalizedSurface;
    this.#canvas = backingCanvas;
    this.#syncRawViewSize();
    this.#syncSurfaceBindingSize(this.#logicalWidth, this.#logicalHeight);
    this.#presentSurface();
    return this;
  }

  detachSurface(options = {}) {
    if (!this.#surface && !this.#canvas) {
      return this;
    }
    this.#clearResizeSnapshot();

    if (options.clear !== false) {
      this.#clearSurfaceContents();
    }
    if (typeof this.#rawView.clearSurface === 'function') {
      this.#rawView.clearSurface();
    }
    if (typeof this.#rawView.setHostCapabilities === 'function') {
      this.#rawView.setHostCapabilities(null);
    }
    this.#surfaceBinding = null;
    this.#surface = null;
    this.#canvas = null;
    return this;
  }

  getSurface() {
    return cloneSurfaceDescriptor(this.#surface);
  }

  bindCanvas(canvas) {
    return this.attachSurface({ type: 'html-canvas', canvas });
  }

  bindDomEvents(options = {}) {
    const canvas = options.canvas || this.#canvas;
    if (!canvas) {
      throw new Error('bindDomEvents requires a bound canvas.');
    }

    if (this.#domCleanup) {
      this.#domCleanup();
    }

    const keyboardTarget = options.keyboardTarget || globalThis.window || canvas;
    const focusTarget = options.focusTarget || canvas;
    const syncFocus = options.syncFocus === true;
    const listeners = [];
    const addListener = (target, type, listener, listenerOptions) => {
      if (!target || typeof target.addEventListener !== 'function') {
        return;
      }
      target.addEventListener(type, listener, listenerOptions);
      listeners.push(() => target.removeEventListener(type, listener, listenerOptions));
    };

    if (
      syncFocus &&
      focusTarget &&
      typeof focusTarget.setAttribute === 'function' &&
      focusTarget.tabIndex < 0
    ) {
      focusTarget.setAttribute('tabindex', '0');
    }

    const scaleWheelDelta = (event) => {
      const rect = canvas.getBoundingClientRect();
      const scaleX = rect.width > 0 ? canvas.width / rect.width : this.#scaleFactor;
      const scaleY = rect.height > 0 ? canvas.height / rect.height : this.#scaleFactor;
      return {
        deltaX: (event.deltaX || 0) * scaleX,
        deltaY: (event.deltaY || 0) * scaleY,
      };
    };

    addListener(canvas, 'pointerdown', (event) => {
      const { x, y } = getPointerPosition(canvas, event);
      this.notifyUserInteraction();
      if (syncFocus && focusTarget && typeof focusTarget.focus === 'function') {
        focusTarget.focus();
      }
      this.#rawView.dispatchPointer(
        'pointerdown',
        x,
        y,
        event.pointerId || 0,
        event.button || 0,
        0,
        0,
      );
      this.requestRender();
    });
    addListener(canvas, 'pointermove', (event) => {
      const { x, y } = getPointerPosition(canvas, event);
      this.#rawView.dispatchPointer(
        'pointermove',
        x,
        y,
        event.pointerId || 0,
        event.button || 0,
        0,
        0,
      );
      this.requestRender();
    });
    addListener(canvas, 'pointerup', (event) => {
      const { x, y } = getPointerPosition(canvas, event);
      this.#rawView.dispatchPointer(
        'pointerup',
        x,
        y,
        event.pointerId || 0,
        event.button || 0,
        0,
        0,
      );
      this.requestRender();
    });
    addListener(canvas, 'pointercancel', (event) => {
      const { x, y } = getPointerPosition(canvas, event);
      this.#rawView.dispatchPointer(
        'touchcancel',
        x,
        y,
        event.pointerId || 0,
        event.button || 0,
        0,
        0,
      );
      this.requestRender();
    });
    addListener(
      canvas,
      'wheel',
      (event) => {
        const { x, y } = getPointerPosition(canvas, event);
        const { deltaX, deltaY } = scaleWheelDelta(event);
        if (options.preventWheelDefault !== false && typeof event.preventDefault === 'function') {
          event.preventDefault();
        }
        this.#rawView.dispatchPointer('mousewheel', x, y, 0, 0, deltaX, deltaY);
        this.requestRender();
      },
      { passive: false },
    );

    addListener(keyboardTarget, 'keydown', (event) => {
      this.#rawView.dispatchInput(
        'keydown',
        normalizeKeyboardCode(event),
        Number(event.timeStamp || Date.now()),
      );
      this.requestRender();
    });
    addListener(keyboardTarget, 'keyup', (event) => {
      this.#rawView.dispatchInput(
        'keyup',
        normalizeKeyboardCode(event),
        Number(event.timeStamp || Date.now()),
      );
      this.requestRender();
    });
    if (syncFocus) {
      addListener(focusTarget, 'focus', () => {
        this.focus();
      });
      addListener(focusTarget, 'blur', () => {
        this.blur();
      });
    }

    this.#domCleanup = () => {
      for (const dispose of listeners.splice(0)) {
        dispose();
      }
      this.#domCleanup = null;
    };

    return this.#domCleanup;
  }

  openBundle({ appId, files, initialPage = null, query = null, hostOptions = null }) {
    if (typeof appId !== 'string' || !appId.trim()) {
      throw new Error('`appId` must be a non-empty string.');
    }

    this.#resetCloseRequestedState();
    this.#rawView.openBundle(
      appId,
      normalizeBundleFiles(files),
      initialPage,
      serializeQuery(query),
      normalizeOpenHostOptions(hostOptions),
    );
    this.#consumeCloseRequested();
    if (!this.#consumeClosed()) {
      this.requestRender();
    }
    return this;
  }

  open(path, initialPage = null, query = null, hostOptions = null) {
    if (typeof path !== 'string' || !path.trim()) {
      throw new Error('`path` must be a non-empty string.');
    }

    this.#resetCloseRequestedState();
    this.#rawView.open(
      path.trim(),
      initialPage,
      serializeQuery(query),
      normalizeOpenHostOptions(hostOptions),
    );
    this.#consumeCloseRequested();
    if (!this.#consumeClosed()) {
      this.requestRender();
    }
    return this;
  }

  async openFromVfs({
    appId,
    baseUrl,
    initialPage = null,
    query = null,
    fetch,
    signal,
    headers,
    requestInterceptor,
    hostOptions = null,
  }) {
    const bundle = await loadBundleFromVfs({
      appId,
      baseUrl,
      fetch,
      signal,
      headers,
      requestInterceptor,
    });
    this.openBundle({
      appId: bundle.appId,
      files: bundle.files,
      initialPage,
      query,
      hostOptions,
    });
    return bundle;
  }

  setViewport(width, height, scaleFactorOrOptions = getDefaultScaleFactor()) {
    const hasResizeOptions = isResizeOptions(scaleFactorOrOptions);
    const shouldResetScroll = hasResizeOptions && scaleFactorOrOptions.resetScroll === true;
    const nextLayoutMode = hasResizeOptions
      ? scaleFactorOrOptions.layoutMode == null
        ? this.#layoutMode
        : normalizeLayoutMode(scaleFactorOrOptions.layoutMode)
      : this.#layoutMode;
    const nextScaleFactor = hasResizeOptions
      ? Object.prototype.hasOwnProperty.call(scaleFactorOrOptions, 'scaleFactor')
        ? normalizeScaleFactor(scaleFactorOrOptions.scaleFactor)
        : this.#scaleFactor
      : normalizeScaleFactor(scaleFactorOrOptions);
    const nextWidth = Math.max(1, Number(width) || 1);
    const fallbackHeight =
      nextLayoutMode === 'width-constrained-auto-height'
        ? this.#logicalHeight
        : this.#boundedHeight;
    const nextHeight = Math.max(1, Number(height) || fallbackHeight || 1);
    const layoutModeChanged = nextLayoutMode !== this.#layoutMode;

    this.#logicalWidth = nextWidth;
    this.#logicalHeight = nextHeight;
    this.#scaleFactor = nextScaleFactor;
    if (nextLayoutMode === 'bounded') {
      this.#boundedHeight = nextHeight;
    }

    if (layoutModeChanged) {
      this.#layoutMode = nextLayoutMode;
      this.#latestContentSize = null;
      this.#pendingAutoHeightContentSize = null;
      this.#clearResizeSnapshot();
      if (typeof this.#rawView.setLayoutMode === 'function') {
        this.#rawView.setLayoutMode(nextLayoutMode);
      }
    }

    this.#syncRawViewSize(this.#logicalWidth, this.#logicalHeight, shouldResetScroll);
    this.#syncSurfaceForCurrentLayoutMode();
    this.#presentSurface({ useResizeSnapshot: true });
    this.requestRender();
    return this;
  }

  resize(width, height, scaleFactorOrOptions = getDefaultScaleFactor()) {
    return this.setViewport(width, height, scaleFactorOrOptions);
  }

  setLayoutMode(layoutMode) {
    const nextLayoutMode = normalizeLayoutMode(layoutMode);
    if (this.#layoutMode === nextLayoutMode) {
      return this;
    }

    const currentContentSize = this.#currentContentSize();
    this.#layoutMode = nextLayoutMode;
    this.#latestContentSize = currentContentSize;
    this.#rawView.setLayoutMode(nextLayoutMode);

    if (nextLayoutMode === 'bounded') {
      this.#logicalHeight = this.#boundedHeight;
      this.#syncRawViewSize();
      this.#syncBoundSurfaceSize();
    } else if (currentContentSize) {
      this.#syncHtmlCanvasAutoHeight(currentContentSize);
    } else {
      this.#syncRawViewSize();
      this.#syncAutoHeightSurfaceSize();
    }

    this.requestRender();
    return this;
  }

  focus() {
    this.#rawView.focus();
    this.requestRender();
    return this;
  }

  blur() {
    this.#rawView.blur();
    this.requestRender();
    return this;
  }

  setTarget(target) {
    this.#rawView.setTarget(normalizeHostTarget(target));
    this.requestRender();
    return this;
  }

  setInteractive(interactive) {
    this.#rawView.setInteractive(Boolean(interactive));
    this.requestRender();
    return this;
  }

  isInteractive() {
    return Boolean(this.#rawView.isInteractive());
  }

  notifyUserInteraction() {
    this.#rawView.notifyUserInteraction();
    return this;
  }

  setHostCapabilities(capabilities) {
    const normalizedCapabilities = normalizeHostCapabilities(
      capabilities,
      this.#hostCapabilitiesTarget,
    );
    if (typeof this.#rawView.setHostCapabilities === 'function') {
      this.#rawView.setHostCapabilities(normalizedCapabilities);
    }
    return this;
  }

  getHostCapabilitiesTarget() {
    return this.#hostCapabilitiesTarget;
  }

  get hostCapabilitiesTarget() {
    return this.#hostCapabilitiesTarget;
  }

  dispatchPointer(eventType, x, y, id = 0, button = 0, deltaX = 0, deltaY = 0) {
    this.#rawView.dispatchPointer(eventType, x, y, id, button, deltaX, deltaY);
    this.requestRender();
    return this;
  }

  dispatchInput(eventType, code, timestamp = Date.now()) {
    this.#rawView.dispatchInput(eventType, code, Number(timestamp));
    this.requestRender();
    return this;
  }

  dispatchVoiceWakeup(keyword, timestamp = Date.now()) {
    if (typeof keyword !== 'string' || !keyword.trim()) {
      throw new TypeError('`keyword` must be a non-empty string.');
    }
    const handled = this.#rawView.dispatchVoiceWakeup(keyword, Number(timestamp));
    this.requestRender();
    return handled !== false;
  }

  dispatchConnectivityEvent(status) {
    this.#rawView.dispatchConnectivityEvent(normalizeConnectivityStatus(status));
    this.requestRender();
    return this;
  }

  dispatchMessageEvent(dataOrJson, origin = 'host', lastEventId = '', format = 'auto') {
    const metadata = normalizeHostMessageMetadata(origin, lastEventId);
    const payload = serializeHostMessagePayload(dataOrJson, format);
    this.#rawView.dispatchMessageEvent(
      payload.payloadText,
      metadata.origin,
      metadata.lastEventId,
      payload.format,
    );
    this.requestRender();
    return this;
  }

  createMessageStream(origin = 'host', lastEventId = '') {
    const metadata = normalizeHostMessageMetadata(origin, lastEventId);
    return new InkHostMessageStream(
      this.#rawView.createMessageStream(metadata.origin, metadata.lastEventId),
      () => this.requestRender(),
    );
  }

  render() {
    if (this.#destroyed || this.#closeRequested) {
      return false;
    }
    const appliedAutoHeight = this.#applyPendingAutoHeightContentSize();
    const hasMoreWork = Boolean(this.#rawView.render());
    const contentSizeChanged = this.#consumeContentSizeChanged();
    this.#consumeMessageEvent();
    this.#consumePerformanceEntry();
    if (hasMoreWork) {
      this.#clearResizeSnapshot();
      this.#presentSurface();
    } else if (appliedAutoHeight) {
      this.#presentSurface({ useResizeSnapshot: true });
    }
    this.#consumeCloseRequested();
    const closeRequested = this.#consumeClosed();
    if (
      !closeRequested &&
      (hasMoreWork || contentSizeChanged || appliedAutoHeight || this.#autoRender)
    ) {
      this.requestRender();
    }
    return !closeRequested && hasMoreWork;
  }

  requestRender() {
    if (this.#destroyed || this.#closeRequested) {
      return this;
    }
    if (this.#frameHandle != null) {
      return this;
    }

    this.#frameHandle = this.#animationFrameApi.request(() => {
      this.#frameHandle = null;
      this.render();
    });
    return this;
  }

  startRendering() {
    if (this.#closeRequested) {
      return this;
    }
    this.#autoRender = true;
    this.requestRender();
    return this;
  }

  stopRendering() {
    this.#autoRender = false;
    if (this.#frameHandle != null) {
      this.#animationFrameApi.cancel(this.#frameHandle);
      this.#frameHandle = null;
    }
    return this;
  }

  isRunning() {
    return !this.#closeRequested && Boolean(this.#rawView.isRunning());
  }

  isDestroyed() {
    return this.#destroyed;
  }

  isRendering() {
    return this.#autoRender;
  }

  setOnCloseRequested(callback) {
    if (callback != null && typeof callback !== 'function') {
      throw new TypeError('`callback` must be a function or null.');
    }
    this.onCloseRequested = callback || null;
    return this;
  }

  get onCloseRequested() {
    return this.#onCloseRequested;
  }

  set onCloseRequested(callback) {
    if (callback != null && typeof callback !== 'function') {
      throw new TypeError('`callback` must be a function or null.');
    }
    this.#onCloseRequested = callback || null;
  }

  setOnClosed(callback) {
    if (callback != null && typeof callback !== 'function') {
      throw new TypeError('`callback` must be a function or null.');
    }
    this.onClosed = callback || null;
    return this;
  }

  get onClosed() {
    return this.#onClosed;
  }

  set onClosed(callback) {
    if (callback != null && typeof callback !== 'function') {
      throw new TypeError('`callback` must be a function or null.');
    }
    this.#onClosed = callback || null;
  }

  setOnContentSizeChanged(callback) {
    if (callback != null && typeof callback !== 'function') {
      throw new TypeError('`callback` must be a function or null.');
    }
    this.onContentSizeChanged = callback || null;
    return this;
  }

  get onContentSizeChanged() {
    return this.#onContentSizeChanged;
  }

  set onContentSizeChanged(callback) {
    if (callback != null && typeof callback !== 'function') {
      throw new TypeError('`callback` must be a function or null.');
    }
    this.#onContentSizeChanged = callback || null;
  }

  setOnMessage(callback) {
    if (callback != null && typeof callback !== 'function') {
      throw new TypeError('`callback` must be a function or null.');
    }
    this.onMessage = callback || null;
    return this;
  }

  get onMessage() {
    return this.#onMessage;
  }

  set onMessage(callback) {
    if (callback != null && typeof callback !== 'function') {
      throw new TypeError('`callback` must be a function or null.');
    }
    this.#onMessage = callback || null;
  }

  setOnPerformanceEntry(callback) {
    if (callback != null && typeof callback !== 'function') {
      throw new TypeError('`callback` must be a function or null.');
    }
    this.onPerformanceEntry = callback || null;
    return this;
  }

  get onPerformanceEntry() {
    return this.#onPerformanceEntry;
  }

  set onPerformanceEntry(callback) {
    if (callback != null && typeof callback !== 'function') {
      throw new TypeError('`callback` must be a function or null.');
    }
    this.#onPerformanceEntry = callback || null;
  }

  isCloseRequested() {
    return this.#closeRequested;
  }

  destroy() {
    if (this.#destroyed) {
      return;
    }
    this.#destroyed = true;
    this.stopRendering();
    if (this.#clearOnDestroy) {
      this.#clearSurfaceContents();
    }
    if (this.#domCleanup) {
      this.#domCleanup();
    }
    if (typeof this.#rawView.clearSurface === 'function') {
      this.#rawView.clearSurface();
    }
    this.#surfaceBinding = null;
    this.#surface = null;
    this.#canvas = null;
    this.#rawView.destroy();
  }
}

export function createInkView(options) {
  return InkView.create(options);
}

export function createFrameworkBindings() {
  return {
    ensureLeadingSlash,
    initInk,
    createInkView,
    configureNetwork,
    setInkRequestInterceptor,
    clearInkRequestInterceptor,
    createProxyUrlResolver,
  };
}
