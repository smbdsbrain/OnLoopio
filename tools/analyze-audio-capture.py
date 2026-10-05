"""Analyze synthetic 48 kHz AUX captures. Requires NumPy/SciPy; writes evidence, not a qualification claim."""
import argparse
import json
import wave
from pathlib import Path
import numpy as np
from scipy.signal import fftconvolve, find_peaks


def read(path):
    with wave.open(str(path)) as f:
        if f.getsampwidth() != 2 or f.getframerate() != 48000 or f.getnchannels() != 1:
            raise ValueError('48 kHz mono PCM16 required')
        frames = f.getnframes()
        data = f.readframes(frames)
        if len(data) != frames * 2:
            raise ValueError('Truncated capture: WAV header exceeds durable sample count')
        return np.frombuffer(data, dtype='<i2').astype(np.float64) / 32768


def matches(capture, template, minimum=.12):
    # Reject DC/microphone handling noise. Filtering the same template makes phase common.
    template = template - template.mean()
    dot = fftconvolve(capture, template[::-1], mode='valid')
    energy = np.cumsum(np.r_[0., capture * capture])
    energy = energy[len(template):] - energy[:-len(template)]
    mean = np.cumsum(np.r_[0., capture])
    mean = mean[len(template):] - mean[:-len(template)]
    energy = np.maximum(1e-15, energy - mean * mean / len(template))
    correlation = dot / np.sqrt(energy * np.dot(template, template))
    starts, _ = find_peaks(np.abs(correlation), height=minimum, distance=int(1.9 * 48000))
    return starts, correlation, dot


def offset(capture, reference, start, at):
    # A local reference window distinguishes latency from amplitude and is away from the boundary.
    index = int(at * 48000)
    window = reference[index:index + 4800]
    begin = int(start) + index - 2400
    signal = capture[begin:begin + 9600]
    if len(signal) != 9600:
        raise ValueError('Incomplete event')
    correlation = fftconvolve(signal, window[::-1], mode='valid')
    lag = int(np.argmax(np.abs(correlation)))
    gain = float(correlation[lag] / np.dot(window, window))
    return lag - 2400, abs(gain)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('reference', type=Path)
    p.add_argument('capture', type=Path)
    p.add_argument('--mode', choices=['gain', 'pair'], required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--minimum-correlation', type=float, default=.12)
    p.add_argument('--capture-path', choices=['unspecified', 'winmm', 'wasapi-raw-requested'], default='unspecified')
    a = p.parse_args()
    reference, capture = read(a.reference), read(a.capture)
    if not .05 <= a.minimum_correlation <= 1:
        p.error('Minimum correlation must be .05..1')
    starts, correlations, _ = matches(capture, reference[:38400], a.minimum_correlation)
    events = []
    for start in starts:
        before, gain = offset(capture, reference, start, .6)
        after, _ = offset(capture, reference, start, 1.3)
        events.append({'start_seconds': float(start / 48000), 'correlation': float(abs(correlations[start])),
                       'gain': gain, 'boundary_displacement_samples': after - before})
    result = {'rate': 48000, 'events': events, 'minimum_correlation': a.minimum_correlation, 'capture_path': a.capture_path, 'clipped_fraction': float(np.mean(np.abs(capture) >= .999)),
              'method': '100 ms local reference correlation before/after boundary; adjacent whole-file control subtracts shared clock drift',
              'limitations': 'Independent ADC/DAC clocks and hardware microphone processing are not controlled; RAW request acceptance alone does not prove bypass. Gain is reference projection, not calibrated RMS; no Bluetooth claim.'}
    if len(events) % 2:
        result['error'] = 'Odd event count: capture is incomplete or noisy'
    elif a.mode == 'gain':
        values = [20 * np.log10(events[n+1]['gain'] / events[n]['gain']) for n in range(0, len(events), 2)]
        result['attenuation_db'] = values
        result['range_db'] = [float(min(values)), float(max(values))] if values else []
    else:
        values = [events[n+1]['boundary_displacement_samples'] - events[n]['boundary_displacement_samples']
                  for n in range(0, len(events), 2)]
        result['additional_boundary_samples'] = values
        result['range_samples'] = [min(values), max(values)] if values else []
    a.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ('events', 'attenuation_db', 'additional_boundary_samples')}, indent=2))
    print('Detected events:', len(events))


if __name__ == '__main__':
    main()
