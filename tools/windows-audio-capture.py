"""Record explicitly connected test equipment via WinMM; WAV output belongs in local/."""
import argparse
import ctypes as c
from ctypes import wintypes as w
import time
import wave
from pathlib import Path


class Format(c.Structure):
    _fields_ = [('tag', w.WORD), ('channels', w.WORD), ('rate', w.DWORD),
                ('bytes_per_second', w.DWORD), ('align', w.WORD), ('bits', w.WORD), ('extra', w.WORD)]


class Header(c.Structure):
    _fields_ = [('data', c.c_void_p), ('length', w.DWORD), ('recorded', w.DWORD),
                ('user', c.c_size_t), ('flags', w.DWORD), ('loops', w.DWORD),
                ('next', c.c_void_p), ('reserved', c.c_size_t)]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--device', type=int, default=0)
    p.add_argument('--seconds', type=float, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if not 0 < a.seconds <= 600:
        p.error('Capture duration must be 0..600 seconds')
    api = c.WinDLL('winmm')
    handle = c.c_void_p()
    rate = 48000
    fmt = Format(1, 1, rate, rate * 2, 2, 16, 0)

    def check(result):
        if result:
            raise RuntimeError('WinMM error ' + str(result))

    check(api.waveInOpen(c.byref(handle), a.device, c.byref(fmt), 0, 0, 0))
    buffers = [c.create_string_buffer(rate * 2) for _ in range(4)]
    headers = [Header(c.cast(buf, c.c_void_p), len(buf), 0, 0, 0, 0, None, 0) for buf in buffers]
    try:
        for h in headers:
            check(api.waveInPrepareHeader(handle, c.byref(h), c.sizeof(h)))
            check(api.waveInAddBuffer(handle, c.byref(h), c.sizeof(h)))
        a.output.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(a.output), 'wb') as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(rate)
            check(api.waveInStart(handle))
            print('Recording started', flush=True)
            deadline = time.monotonic() + a.seconds
            index = 0
            while time.monotonic() < deadline:
                h = headers[index]
                if h.flags & 1:
                    out.writeframes(c.string_at(h.data, h.recorded))
                    h.recorded = 0
                    check(api.waveInAddBuffer(handle, c.byref(h), c.sizeof(h)))
                    index = (index + 1) % len(headers)
                else:
                    time.sleep(.005)
            check(api.waveInReset(handle))
            for offset in range(len(headers)):
                h = headers[(index + offset) % len(headers)]
                if h.recorded:
                    out.writeframes(c.string_at(h.data, h.recorded))
    finally:
        api.waveInReset(handle)
        for h in headers:
            api.waveInUnprepareHeader(handle, c.byref(h), c.sizeof(h))
        api.waveInClose(handle)
    print('Recording saved: ' + str(a.output))


if __name__ == '__main__':
    main()
