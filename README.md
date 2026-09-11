# AudioMix

A pure-OOP Java library modeling an **advanced audio mixing console**: any number
of channel strips (source, gain, pan, ordered insert-effect chain, sends to shared
buses), aux/FX buses that are mixers themselves, a master section, and multiple
physical outputs. One block-based pull engine drives both **real-time playback**
and **offline render-to-WAV**. Zero external dependencies.

## Features

- Console domain model: channels, sends (pre/post-fader), aux/FX buses, master, multi-output device binding
- Block-based engine — fixed 512-frame blocks, non-interleaved float samples, ±1.0 nominal range
- Real-time and offline through the same code path
- Thread-safe live parameters: setters from any thread, ~10 ms ramp-smoothed gain/send/pan, click-free
- Built-in effects catalog: parametric EQ, delay/echo, compressor/limiter, reverb, meter (planned: chorus, flanger, pitch shift, spectrum analyzer)
- WAV I/O: PCM 16/24-bit and 32-bit float, mono/stereo; AIFF read; auto-resampling to the project rate
- Pure Java — no JNI, no native libs, no GPL code

## Status

Work in progress. Core engine and Tier-1 effects are being built step by step; every step lands with a full green test suite.

## Requirements

- Java 17+
- Maven 3.9+

## Build & test

```bash
mvn test
```

## Usage (target API)

```java
Mixer m = new Mixer(48000);
Channel voc = m.addChannel("vocals");
voc.setSource(new FileSource("vocals.wav"));
voc.addEffect(new ParametricEq(48000).band(EqType.PEAK, 1000, 3.0, 1.0f));
voc.setGainDb(-3);
voc.addSend(m.getAux("reverb"), 0.4);

m.getMaster().bindToDefaultSpeakers();   // real-time
m.renderToFile("mix.wav");               // offline — same model
```

## Design notes

- **No locks on the audio path** — parameters are volatile targets consumed at block boundaries.
- **Fail-silent audio path** — a per-block exception degrades that contribution to silence + a log warning, never propagates.
- **Open interfaces** — `Source` and `Effect` are small; bring your own (MP3 decode, custom DSP).

## Non-goals (v1)

MIDI/VST hosting, surround master, MP3 decode, persistence, automation curves, GUI.

## License

[MIT](LICENSE)
