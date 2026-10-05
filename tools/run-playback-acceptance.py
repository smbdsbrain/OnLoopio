"""Run bounded powered playback qualification and restore the selected test target."""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--adb', required=True, type=Path)
    p.add_argument('--package', choices=['io.onloopio', 'io.onloopio.validation'], required=True)
    p.add_argument('--minutes', type=int, default=120)
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--restore-network', action='store_true')
    p.add_argument('--uninstall-tests', action='store_true')
    a = p.parse_args()
    if not 1 <= a.minutes <= 240:
        p.error('minutes must be 1..240')
    a.output.mkdir(parents=True, exist_ok=True)
    state_path = a.output / 'result.json'
    state = {'status': 'running', 'started_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
             'minutes': a.minutes, 'package': a.package, 'steps': [],
             'scope': 'powered offline playback/focus/navigation; no battery, BT, USB or SD-removal claim'}

    def save():
        temporary = state_path.with_suffix('.tmp')
        temporary.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
        temporary.replace(state_path)

    def run(name, args, timeout, expected=None):
        path = a.output / (name + '.txt')
        ok = False
        try:
            with path.open('w', encoding='utf-8') as log:
                completed = subprocess.run([str(a.adb)] + args, stdout=log, stderr=subprocess.STDOUT,
                                           timeout=timeout, creationflags=0x08000000 if sys.platform=='win32' else 0)
            output = path.read_text(encoding='utf-8', errors='replace')
            ok = completed.returncode == 0 and (expected is None or expected in output)
        except Exception as error:
            with path.open('a', encoding='utf-8') as log:
                log.write('\nHost failure: ' + type(error).__name__ + '\n')
        state['steps'].append({'name': name, 'ok': ok, 'log': str(path.resolve())})
        save()
        return ok

    def instrument(probe, *args):
        return ['shell', 'am', 'instrument', '-w', *args, a.package + '.tests/io.onloopio.' + probe]

    save()
    try:
        ok = run('soak', instrument('PlaybackAcceptanceProbe', '-e', 'operation', 'soak', '-e', 'minutes', str(a.minutes)),
                 (a.minutes + 10) * 60, 'Playback acceptance soak PASS')
        if not ok:
            run('stop-interrupted', ['shell', 'am', 'force-stop', a.package], 30)
        ok = run('cleanup', instrument('PlaybackAcceptanceProbe', '-e', 'operation', 'cleanup'), 180,
                 'Playback acceptance cleanup PASS') and ok
        if a.restore_network:
            ok = run('restore-network', instrument('NetworkDeviceProbe', '-e', 'operation', 'restore'), 180,
                     'Network probe restore passed') and ok
        if a.uninstall_tests:
            ok = run('uninstall-tests', ['uninstall', a.package + '.tests'], 60, 'Success') and ok
        state['status'] = 'passed' if ok else 'failed'
    finally:
        state['finished_utc'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
        if state['status'] == 'running':
            state['status'] = 'failed'
        save()
    print(json.dumps(state, indent=2))
    return 0 if state['status'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
