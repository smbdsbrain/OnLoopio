"""Check capture-analysis precision against known synthetic sample insertions and ADC noise."""
import importlib.util
import pathlib
import unittest
import tempfile
import wave
import numpy as np

spec = importlib.util.spec_from_file_location('analysis', pathlib.Path(__file__).with_name('analyze-audio-capture.py'))
analysis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analysis)


class AnalysisTest(unittest.TestCase):
    def test_truncated_capture_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / 'capture.wav'
            with wave.open(str(path), 'wb') as output:
                output.setnchannels(1)
                output.setsampwidth(2)
                output.setframerate(48000)
                output.writeframes(b'\x00\x00' * 100)
            path.write_bytes(path.read_bytes()[:-20])
            with self.assertRaisesRegex(ValueError, 'Truncated capture'):
                analysis.read(path)

    def test_insertions_and_gain(self):
        random = np.random.default_rng(17017)
        reference = np.convolve(random.normal(size=96000), np.ones(6)/6, mode='same')*.05
        for gap in (0, 1, 17, 240):
            pair = np.r_[reference[:48000], np.zeros(gap), reference[48000:]]
            capture = np.r_[np.zeros(4800), pair] + random.normal(0, .002, 4800+len(pair))
            before, gain = analysis.offset(capture, reference, 4800, .6)
            after, _ = analysis.offset(capture, reference, 4800, 1.3)
            self.assertLessEqual(abs(after-before-gap), 1)
            self.assertLess(abs(gain-1), .02)
        capture = np.r_[np.zeros(4800), reference*.501187] + random.normal(0, .002, 100800)
        _, gain = analysis.offset(capture, reference, 4800, .6)
        self.assertLess(abs(20*np.log10(gain)+6), .15)


if __name__ == '__main__':
    unittest.main()
