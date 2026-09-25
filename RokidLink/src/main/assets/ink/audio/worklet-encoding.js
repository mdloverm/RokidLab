// AudioWorkletGlobalScope does not consistently provide Encoding API globals.
// These small UTF-8 implementations initialize wasm-bindgen's string bridge.
if (typeof globalThis.TextEncoder === 'undefined') {
  globalThis.TextEncoder = class TextEncoder {
    encode(value = '') {
      const bytes = [];
      for (const char of String(value)) {
        let c = char.codePointAt(0);
        if (c >= 0xd800 && c <= 0xdfff) c = 0xfffd;
        if (c < 0x80) bytes.push(c);
        else if (c < 0x800) bytes.push(0xc0 | (c >> 6), 0x80 | (c & 63));
        else if (c < 0x10000) bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
        else
          bytes.push(
            0xf0 | (c >> 18),
            0x80 | ((c >> 12) & 63),
            0x80 | ((c >> 6) & 63),
            0x80 | (c & 63),
          );
      }
      return new Uint8Array(bytes);
    }
    encodeInto(value, destination) {
      let read = 0;
      let written = 0;
      for (const char of value) {
        const bytes = this.encode(char);
        if (written + bytes.length > destination.length) break;
        destination.set(bytes, written);
        written += bytes.length;
        read += char.length;
      }
      return { read, written };
    }
  };
}
if (typeof globalThis.TextDecoder === 'undefined') {
  globalThis.TextDecoder = class TextDecoder {
    decode(input = new Uint8Array()) {
      const bytes =
        input instanceof Uint8Array
          ? input
          : new Uint8Array(input.buffer ?? input, input.byteOffset ?? 0, input.byteLength);
      let result = '';
      for (let i = 0; i < bytes.length; ) {
        const first = bytes[i++];
        let code = first;
        let remaining = 0;
        if (first >= 0xf0) {
          code = first & 7;
          remaining = 3;
        } else if (first >= 0xe0) {
          code = first & 15;
          remaining = 2;
        } else if (first >= 0xc0) {
          code = first & 31;
          remaining = 1;
        }
        for (let n = 0; n < remaining; n++) code = (code << 6) | (bytes[i++] & 63);
        result += String.fromCodePoint(code <= 0x10ffff ? code : 0xfffd);
      }
      return result;
    }
  };
}
