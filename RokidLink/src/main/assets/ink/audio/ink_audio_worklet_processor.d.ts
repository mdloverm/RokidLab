/* tslint:disable */
/* eslint-disable */

export class AudioProcessor {
    free(): void;
    [Symbol.dispose](): void;
    buffer(index: number, generation: number, rate: number, channels: number, pcm: Float32Array): void;
    collect(): void;
    /**
     * Called by the message task, never from process().
     */
    command(json: string): void;
    events(): string;
    frame(): number;
    has_events(): boolean;
    history(index: number, generation: number): number;
    input_memory(): number;
    media_input(index: number, generation: number, channels: number): void;
    constructor(rate: number);
    render(): number;
}

export function audio_memory(): any;

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
    readonly memory: WebAssembly.Memory;
    readonly __wbg_audioprocessor_free: (a: number, b: number) => void;
    readonly audioprocessor_buffer: (a: number, b: number, c: number, d: number, e: number, f: number, g: number, h: number) => void;
    readonly audioprocessor_collect: (a: number) => void;
    readonly audioprocessor_command: (a: number, b: number, c: number, d: number) => void;
    readonly audioprocessor_events: (a: number, b: number) => void;
    readonly audioprocessor_frame: (a: number) => number;
    readonly audioprocessor_has_events: (a: number) => number;
    readonly audioprocessor_history: (a: number, b: number, c: number, d: number) => void;
    readonly audioprocessor_input_memory: (a: number) => number;
    readonly audioprocessor_media_input: (a: number, b: number, c: number, d: number, e: number) => void;
    readonly audioprocessor_new: (a: number, b: number) => void;
    readonly audioprocessor_render: (a: number) => number;
    readonly audio_memory: () => number;
    readonly __wbindgen_add_to_stack_pointer: (a: number) => number;
    readonly __wbindgen_export: (a: number, b: number) => number;
    readonly __wbindgen_export2: (a: number, b: number, c: number, d: number) => number;
    readonly __wbindgen_export3: (a: number, b: number, c: number) => void;
}

export type SyncInitInput = BufferSource | WebAssembly.Module;

/**
 * Instantiates the given `module`, which can either be bytes or
 * a precompiled `WebAssembly.Module`.
 *
 * @param {{ module: SyncInitInput }} module - Passing `SyncInitInput` directly is deprecated.
 *
 * @returns {InitOutput}
 */
export function initSync(module: { module: SyncInitInput } | SyncInitInput): InitOutput;

/**
 * If `module_or_path` is {RequestInfo} or {URL}, makes a request and
 * for everything else, calls `WebAssembly.instantiate` directly.
 *
 * @param {{ module_or_path: InitInput | Promise<InitInput> }} module_or_path - Passing `InitInput` directly is deprecated.
 *
 * @returns {Promise<InitOutput>}
 */
export default function __wbg_init (module_or_path?: { module_or_path: InitInput | Promise<InitInput> } | InitInput | Promise<InitInput>): Promise<InitOutput>;
