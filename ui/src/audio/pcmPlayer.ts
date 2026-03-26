export type PcmPlayerStats = {
  queuedChunks: number;
  queuedBytes: number;
  nextPlaybackTime: number;
  initialized: boolean;
};

function decodeBase64ToInt16(base64: string): Int16Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return new Int16Array(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
}

function int16ToFloat32(input: Int16Array): Float32Array {
  const output = new Float32Array(input.length);
  for (let index = 0; index < input.length; index += 1) {
    output[index] = Math.max(-1, input[index] / 32768);
  }
  return output;
}

export class PcmPlayer {
  private audioContext: AudioContext | null = null;
  private nextPlaybackTime = 0;
  private queuedChunks = 0;
  private queuedBytes = 0;

  async ensureReady(): Promise<void> {
    if (!this.audioContext) {
      this.audioContext = new AudioContext({ sampleRate: 24000 });
      this.nextPlaybackTime = this.audioContext.currentTime;
    }
    if (this.audioContext.state === "suspended") {
      await this.audioContext.resume();
    }
  }

  async enqueueBase64Pcm(base64: string, sampleRate: number): Promise<void> {
    await this.ensureReady();
    if (!this.audioContext) {
      return;
    }
    const pcm = decodeBase64ToInt16(base64);
    const float32 = int16ToFloat32(pcm);
    const buffer = this.audioContext.createBuffer(1, float32.length, sampleRate);
    buffer.getChannelData(0).set(float32);
    const source = this.audioContext.createBufferSource();
    source.buffer = buffer;
    source.connect(this.audioContext.destination);
    const startAt = Math.max(this.audioContext.currentTime, this.nextPlaybackTime);
    source.start(startAt);
    this.nextPlaybackTime = startAt + buffer.duration;
    this.queuedChunks += 1;
    this.queuedBytes += pcm.byteLength;
    source.addEventListener("ended", () => {
      this.queuedChunks = Math.max(0, this.queuedChunks - 1);
      this.queuedBytes = Math.max(0, this.queuedBytes - pcm.byteLength);
    });
  }

  getStats(): PcmPlayerStats {
    return {
      queuedChunks: this.queuedChunks,
      queuedBytes: this.queuedBytes,
      nextPlaybackTime: this.nextPlaybackTime,
      initialized: Boolean(this.audioContext)
    };
  }
}