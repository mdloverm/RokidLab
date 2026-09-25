let wasmExports = null;
const allocationSizes = new Map();
const textDecoder = new TextDecoder('utf-8', { fatal: false });
const textEncoder = new TextEncoder();
let inkRequestInterceptor = null;
const hrtimeOriginNs =
  typeof performance !== 'undefined' && typeof performance.now === 'function'
    ? BigInt(Math.floor(performance.now() * 1_000_000))
    : 0n;

export function rquickjs_browser_date_now_us() {
  return BigInt(Date.now()) * 1000n;
}

export function rquickjs_browser_hrtime_ns() {
  if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
    return BigInt(Math.floor(performance.now() * 1_000_000)) - hrtimeOriginNs;
  }
  return BigInt(Date.now()) * 1_000_000n;
}

export function __setWasmInstance(exports) {
  wasmExports = exports;
}

function ensureFetchImplementation(fetchImpl) {
  const resolved = fetchImpl || globalThis.fetch;
  if (typeof resolved !== 'function') {
    throw new Error('A fetch implementation is required for Ink networking.');
  }
  return resolved;
}

function headersToObject(headersLike) {
  if (!headersLike) {
    return {};
  }
  if (typeof Headers !== 'undefined' && headersLike instanceof Headers) {
    const result = {};
    for (const [name, value] of headersLike.entries()) {
      result[name] = value;
    }
    return result;
  }
  if (Array.isArray(headersLike)) {
    const result = {};
    for (const entry of headersLike) {
      if (!Array.isArray(entry) || entry.length < 2) {
        continue;
      }
      result[String(entry[0])] = String(entry[1]);
    }
    return result;
  }
  if (headersLike && typeof headersLike === 'object') {
    return Object.fromEntries(
      Object.entries(headersLike).map(([name, value]) => [
        name,
        Array.isArray(value) ? value.join(', ') : String(value),
      ]),
    );
  }
  return {};
}

function cloneBody(body) {
  if (body == null) {
    return null;
  }
  if (typeof body === 'string') {
    return body;
  }
  if (body instanceof Uint8Array) {
    return new Uint8Array(body);
  }
  if (ArrayBuffer.isView(body)) {
    return new Uint8Array(body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength));
  }
  if (body instanceof ArrayBuffer) {
    return body.slice(0);
  }
  return body;
}

function normalizeRequest(input, init = {}, metadata = {}) {
  const url = typeof input === 'string' ? input : String(input);
  return {
    url,
    method: String(init.method || 'GET').toUpperCase(),
    headers: headersToObject(init.headers),
    body: cloneBody(init.body),
    duplex: init.duplex,
    metadata: metadata && typeof metadata === 'object' ? { ...metadata } : {},
  };
}

function normalizeInterceptorResult(request, result) {
  if (result == null || result === false) {
    return {
      url: request.url,
      init: {
        method: request.method,
        headers: request.headers,
        body: request.body,
        duplex: request.duplex,
      },
    };
  }

  if (typeof result !== 'object') {
    throw new TypeError('Ink request interceptor must return an object, null, or undefined.');
  }

  const action = result.action || 'rewrite';
  if (action === 'continue') {
    return {
      url: request.url,
      init: {
        method: request.method,
        headers: request.headers,
        body: request.body,
        duplex: request.duplex,
      },
    };
  }

  if (action === 'block') {
    throw new Error(result.reason || `Ink request blocked: ${request.url}`);
  }

  if (action !== 'rewrite') {
    throw new Error(`Unsupported Ink request interceptor action: ${action}`);
  }

  return {
    url: typeof result.url === 'string' && result.url ? result.url : request.url,
    init: {
      method: typeof result.method === 'string' && result.method ? result.method : request.method,
      headers: result.headers ? headersToObject(result.headers) : request.headers,
      body: Object.prototype.hasOwnProperty.call(result, 'body') ? result.body : request.body,
      duplex: Object.prototype.hasOwnProperty.call(result, 'duplex')
        ? result.duplex
        : request.duplex,
    },
  };
}

async function resolveInkFetchRequest(input, init = {}, metadata = {}, interceptorOverride = null) {
  const request = normalizeRequest(input, init, metadata);
  const interceptor = interceptorOverride || inkRequestInterceptor;
  if (!interceptor) {
    return {
      url: request.url,
      init: {
        method: request.method,
        headers: request.headers,
        body: request.body,
        duplex: request.duplex,
      },
    };
  }
  const result = await interceptor(request);
  return normalizeInterceptorResult(request, result);
}

export function setInkRequestInterceptor(interceptor) {
  if (interceptor != null && typeof interceptor !== 'function') {
    throw new TypeError('Ink request interceptor must be a function, null, or undefined.');
  }
  inkRequestInterceptor = interceptor || null;
}

export function clearInkRequestInterceptor() {
  inkRequestInterceptor = null;
}

export async function __inkFetch(input, init, metadata) {
  const resolved = await resolveInkFetchRequest(input, init, metadata);
  const fetchImpl = ensureFetchImplementation();
  return fetchImpl(resolved.url, resolved.init);
}

export async function fetchWithInkRequestInterceptor(input, init = {}, options = {}) {
  const resolved = await resolveInkFetchRequest(
    input,
    init,
    options.metadata,
    options.interceptor || null,
  );
  const fetchImpl = ensureFetchImplementation(options.fetch);
  return fetchImpl(resolved.url, resolved.init);
}

globalThis.__inkFetch = __inkFetch;

function requireWasm() {
  if (!wasmExports) {
    throw new Error('Wasm exports are not initialized');
  }
  return wasmExports;
}

function memoryBytes() {
  return new Uint8Array(requireWasm().memory.buffer);
}

function memoryView() {
  return new DataView(requireWasm().memory.buffer);
}

function readCString(ptr) {
  ptr >>>= 0;
  const bytes = memoryBytes();
  let end = ptr;
  while (end < bytes.length && bytes[end] !== 0) {
    end += 1;
  }
  return textDecoder.decode(bytes.subarray(ptr, end));
}

function writeCString(ptr, maxBytes, value) {
  ptr >>>= 0;
  maxBytes >>>= 0;
  if (maxBytes === 0) {
    return textEncoder.encode(value).length;
  }
  const encoded = textEncoder.encode(value);
  const bytes = memoryBytes();
  const writeLen = Math.min(encoded.length, maxBytes - 1);
  bytes.set(encoded.subarray(0, writeLen), ptr);
  bytes[ptr + writeLen] = 0;
  return encoded.length;
}

function trackedMalloc(size, align = 1) {
  const wasm = requireWasm();
  const normalizedSize = Math.max(1, size >>> 0);
  const ptr = wasm.__wbindgen_export(normalizedSize, align >>> 0) >>> 0;
  allocationSizes.set(ptr, normalizedSize);
  return ptr;
}

function trackedFree(ptr) {
  ptr >>>= 0;
  if (ptr === 0) {
    return;
  }
  const wasm = requireWasm();
  const size = allocationSizes.get(ptr);
  if (size === undefined) {
    return;
  }
  allocationSizes.delete(ptr);
  wasm.__wbindgen_export4(ptr, size, 1);
}

function consoleWrite(prefix, text) {
  const value = prefix ? `${prefix}${text}` : text;
  console.log(value);
}

function readCStringWithLength(ptr, maxBytes) {
  ptr >>>= 0;
  const bytes = memoryBytes();
  let end = ptr;
  const maxEnd = Math.min(bytes.length, ptr + Math.max(0, maxBytes | 0));
  while (end < maxEnd && bytes[end] !== 0) {
    end += 1;
  }
  return textDecoder.decode(bytes.subarray(ptr, end));
}

function normalizePrintfValue(value) {
  if (typeof value === 'bigint') {
    return value;
  }
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : 0;
  }
  if (value == null) {
    return 0;
  }
  return Number(value) || 0;
}

function createDirectArgumentReader(values, offset) {
  let index = offset;
  return {
    next() {
      const value = index < values.length ? values[index] : 0;
      index += 1;
      return value;
    },
    nextInt32() {
      return Number(normalizePrintfValue(this.next())) | 0;
    },
    nextUint32() {
      return Number(normalizePrintfValue(this.next())) >>> 0;
    },
    nextPointer() {
      return Number(normalizePrintfValue(this.next())) >>> 0;
    },
    nextFloat64() {
      return Number(normalizePrintfValue(this.next()));
    },
  };
}

function createVaListReader(argsPtr) {
  let cursor = argsPtr >>> 0;
  const view = memoryView();
  const nextSlot = () => {
    const slot = cursor;
    cursor = (cursor + 8) >>> 0;
    return slot;
  };
  return {
    nextInt32() {
      return view.getInt32(nextSlot(), true);
    },
    nextUint32() {
      return view.getUint32(nextSlot(), true);
    },
    nextPointer() {
      return view.getUint32(nextSlot(), true);
    },
    nextFloat64() {
      return view.getFloat64(nextSlot(), true);
    },
  };
}

function formatInteger(
  value,
  { signed = true, base = 10, width = 0, padZero = false, uppercase = false } = {},
) {
  let normalized = normalizePrintfValue(value);
  let negative = false;
  let digits = '';

  if (typeof normalized === 'bigint') {
    if (signed && normalized < 0n) {
      negative = true;
      normalized = -normalized;
    } else if (!signed) {
      normalized = BigInt.asUintN(64, normalized);
    }
    digits = normalized.toString(base);
  } else {
    let numberValue = Math.trunc(Number(normalized) || 0);
    if (signed) {
      negative = numberValue < 0;
      numberValue = Math.abs(numberValue);
      digits = numberValue.toString(base);
    } else {
      digits = (numberValue >>> 0).toString(base);
    }
  }

  if (uppercase) {
    digits = digits.toUpperCase();
  }

  const prefix = negative ? '-' : '';
  const paddedDigits =
    padZero && width > prefix.length + digits.length
      ? digits.padStart(width - prefix.length, '0')
      : digits;
  const result = `${prefix}${paddedDigits}`;
  return !padZero && width > result.length ? result.padStart(width, ' ') : result;
}

function formatStringValue(value, precision) {
  if (typeof value === 'string') {
    return precision == null ? value : value.slice(0, precision);
  }
  const ptr = Number(normalizePrintfValue(value)) >>> 0;
  if (ptr === 0) {
    return '(null)';
  }
  if (precision == null) {
    return readCString(ptr);
  }
  return readCStringWithLength(ptr, precision);
}

function formatPrintf(fmtPtr, reader = null) {
  const fmt = readCString(fmtPtr);
  if (!reader) {
    return fmt;
  }

  let result = '';
  for (let i = 0; i < fmt.length; i += 1) {
    const ch = fmt[i];
    if (ch !== '%') {
      result += ch;
      continue;
    }

    const next = fmt[i + 1];
    if (next === '%') {
      result += '%';
      i += 1;
      continue;
    }

    let cursor = i + 1;
    let padZero = false;
    if (fmt[cursor] === '0') {
      padZero = true;
      cursor += 1;
    }

    let widthText = '';
    while (cursor < fmt.length && /[0-9]/.test(fmt[cursor])) {
      widthText += fmt[cursor];
      cursor += 1;
    }
    const width = widthText ? Number.parseInt(widthText, 10) : 0;

    let precision = null;
    if (fmt[cursor] === '.') {
      cursor += 1;
      if (fmt[cursor] === '*') {
        precision = Math.max(0, reader.nextInt32());
        cursor += 1;
      } else {
        let precisionText = '';
        while (cursor < fmt.length && /[0-9]/.test(fmt[cursor])) {
          precisionText += fmt[cursor];
          cursor += 1;
        }
        precision = precisionText ? Number.parseInt(precisionText, 10) : 0;
      }
    }

    while (cursor < fmt.length && /[hljztL]/.test(fmt[cursor])) {
      cursor += 1;
    }

    const specifier = fmt[cursor];
    if (!specifier) {
      result += '%';
      break;
    }

    switch (specifier) {
      case 's':
        result += formatStringValue(reader.nextPointer(), precision);
        break;
      case 'c':
        result += String.fromCodePoint(reader.nextInt32() >>> 0);
        break;
      case 'd':
      case 'i':
        result += formatInteger(reader.nextInt32(), {
          signed: true,
          base: 10,
          width,
          padZero,
        });
        break;
      case 'u':
        result += formatInteger(reader.nextUint32(), {
          signed: false,
          base: 10,
          width,
          padZero,
        });
        break;
      case 'x':
      case 'X':
        result += formatInteger(reader.nextUint32(), {
          signed: false,
          base: 16,
          width,
          padZero,
          uppercase: specifier === 'X',
        });
        break;
      case 'f':
      case 'g':
        result += String(reader.nextFloat64());
        break;
      default:
        result += `%${fmt.slice(i + 1, cursor + 1)}`;
        break;
    }

    i = cursor;
  }

  return result;
}

function parseNumberPrefix(text) {
  const trimmed = text.match(/^\s*/)?.[0] ?? '';
  const rest = text.slice(trimmed.length);
  const match = rest.match(
    /^[+-]?(?:inf(?:inity)?|nan(?:\([^)]+\))?|(?:(?:\d+\.?\d*)|(?:\.\d+))(?:[eE][+-]?\d+)?)/i,
  );
  if (!match) {
    return { value: 0, consumed: 0 };
  }
  const literal = match[0];
  return {
    value: Number.parseFloat(literal),
    consumed: trimmed.length + literal.length,
  };
}

function dayOfYear(date) {
  const start = Date.UTC(date.getUTCFullYear(), 0, 1);
  const now = Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
  return Math.floor((now - start) / 86400000);
}

export function __assert_fail(assertionPtr, filePtr, line, functionPtr) {
  const assertion = readCString(assertionPtr);
  const file = readCString(filePtr);
  const func = readCString(functionPtr);
  throw new Error(`assertion failed: ${assertion} at ${file}:${line} in ${func}`);
}

export function printf(fmtPtr) {
  const message = formatPrintf(fmtPtr, createDirectArgumentReader(arguments, 1));
  consoleWrite('', message);
  return message.length | 0;
}

export function abort() {
  throw new Error('abort() called from wasm');
}

export function puts(ptr) {
  const message = readCString(ptr);
  consoleWrite('', message);
  return message.length | 0;
}

export function putchar(ch) {
  const value = String.fromCharCode(ch & 0xff);
  consoleWrite('', value);
  return ch | 0;
}

export function fprintf(_stream, fmtPtr) {
  const message = formatPrintf(fmtPtr, createDirectArgumentReader(arguments, 2));
  consoleWrite('', message);
  return message.length | 0;
}

export function fwrite(ptr, size, nmemb, _stream) {
  const total = (size >>> 0) * (nmemb >>> 0);
  const bytes = memoryBytes().subarray(ptr >>> 0, (ptr >>> 0) + total);
  consoleWrite('', textDecoder.decode(bytes));
  return nmemb | 0;
}

export function fputc(ch, _stream) {
  return putchar(ch);
}

export function vsnprintf(dst, size, fmtPtr, argsPtr) {
  return writeCString(dst, size, formatPrintf(fmtPtr, createVaListReader(argsPtr))) | 0;
}

export function snprintf(dst, size, fmtPtr) {
  return (
    writeCString(dst, size, formatPrintf(fmtPtr, createDirectArgumentReader(arguments, 3))) | 0
  );
}

export function scalbn(x, n) {
  return x * Math.pow(2, n | 0);
}

export function lrint(x) {
  if (!Number.isFinite(x)) {
    return 0;
  }
  return Math.round(x) | 0;
}

export function realloc(ptr, size) {
  ptr >>>= 0;
  size >>>= 0;
  if (ptr === 0) {
    return trackedMalloc(size);
  }
  const oldSize = allocationSizes.get(ptr) ?? 0;
  const nextPtr = trackedMalloc(size);
  if (oldSize > 0) {
    const bytes = memoryBytes();
    bytes.copyWithin(nextPtr, ptr, ptr + Math.min(oldSize, size));
    trackedFree(ptr);
  }
  return nextPtr;
}

export function strchr(ptr, ch) {
  ptr >>>= 0;
  const target = ch & 0xff;
  const bytes = memoryBytes();
  for (let i = ptr; i < bytes.length; i += 1) {
    const value = bytes[i];
    if (value === target) {
      return i >>> 0;
    }
    if (value === 0) {
      return target === 0 ? i >>> 0 : 0;
    }
  }
  return 0;
}

export function free(ptr) {
  trackedFree(ptr);
}

export function strncmp(aPtr, bPtr, count) {
  const bytes = memoryBytes();
  let a = aPtr >>> 0;
  let b = bPtr >>> 0;
  const limit = count >>> 0;
  for (let i = 0; i < limit; i += 1) {
    const av = bytes[a + i] ?? 0;
    const bv = bytes[b + i] ?? 0;
    if (av !== bv) {
      return av < bv ? -1 : 1;
    }
    if (av === 0) {
      return 0;
    }
  }
  return 0;
}

export function strcmp(aPtr, bPtr) {
  const bytes = memoryBytes();
  let a = aPtr >>> 0;
  let b = bPtr >>> 0;
  while (true) {
    const av = bytes[a] ?? 0;
    const bv = bytes[b] ?? 0;
    if (av !== bv) {
      return av < bv ? -1 : 1;
    }
    if (av === 0) {
      return 0;
    }
    a += 1;
    b += 1;
  }
}

export function memchr(ptr, ch, count) {
  ptr >>>= 0;
  const target = ch & 0xff;
  const limit = count >>> 0;
  const bytes = memoryBytes();
  for (let i = 0; i < limit; i += 1) {
    if (bytes[ptr + i] === target) {
      return (ptr + i) >>> 0;
    }
  }
  return 0;
}

export function calloc(nmemb, size) {
  const total = (nmemb >>> 0) * (size >>> 0);
  const ptr = trackedMalloc(total);
  memoryBytes().fill(0, ptr, ptr + total);
  return ptr;
}

export function malloc(size) {
  return trackedMalloc(size);
}

export function frexp(value, expPtr) {
  if (value === 0 || !Number.isFinite(value)) {
    memoryView().setInt32(expPtr >>> 0, 0, true);
    return value;
  }
  const exponent = Math.floor(Math.log2(Math.abs(value))) + 1;
  memoryView().setInt32(expPtr >>> 0, exponent, true);
  return value / Math.pow(2, exponent);
}

export function strrchr(ptr, ch) {
  ptr >>>= 0;
  const target = ch & 0xff;
  const bytes = memoryBytes();
  let found = 0;
  for (let i = ptr; i < bytes.length; i += 1) {
    const value = bytes[i];
    if (value === target) {
      found = i >>> 0;
    }
    if (value === 0) {
      return target === 0 ? i >>> 0 : found;
    }
  }
  return found;
}

export function vfprintf(stream, fmtPtr, argsPtr) {
  return fprintf(stream, fmtPtr, argsPtr);
}

export function strtod(ptr, endPtrPtr) {
  const text = readCString(ptr);
  const { value, consumed } = parseNumberPrefix(text);
  if (endPtrPtr >>> 0 !== 0) {
    memoryView().setUint32(endPtrPtr >>> 0, ((ptr >>> 0) + consumed) >>> 0, true);
  }
  return value;
}

export function localtime_r(timePtr, resultPtr) {
  const view = memoryView();
  const ptr = timePtr >>> 0;
  let seconds = 0;
  try {
    seconds = Number(view.getBigInt64(ptr, true));
  } catch {
    seconds = view.getInt32(ptr, true);
  }
  const date = new Date(seconds * 1000);
  const out = resultPtr >>> 0;
  view.setInt32(out + 0, date.getSeconds(), true);
  view.setInt32(out + 4, date.getMinutes(), true);
  view.setInt32(out + 8, date.getHours(), true);
  view.setInt32(out + 12, date.getDate(), true);
  view.setInt32(out + 16, date.getMonth(), true);
  view.setInt32(out + 20, date.getFullYear() - 1900, true);
  view.setInt32(out + 24, date.getDay(), true);
  view.setInt32(out + 28, dayOfYear(date), true);
  view.setInt32(out + 32, 0, true);
  return out;
}

export function acosh(x) {
  return Math.acosh(x);
}

export function asinh(x) {
  return Math.asinh(x);
}

export function atanh(x) {
  return Math.atanh(x);
}
