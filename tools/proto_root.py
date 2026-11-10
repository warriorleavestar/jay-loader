"""Print a root path for the prototype that the JVM can represent.
If the real path is representable in the system ANSI code page (what sun.jnu.encoding follows on Windows), print it.
Otherwise create (once) an English-named directory junction next to it and print that. No admin needed (mklink /J)."""
import ctypes, os, subprocess, sys
real = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JUNCTION = os.path.join(os.path.dirname(real), os.path.basename(real).encode('ascii', 'replace').decode().replace('?', '_') + '-ascii')
def representable(p):
    try: p.encode(f'cp{ctypes.windll.kernel32.GetACP()}', errors='strict'); return True
    except (UnicodeEncodeError, LookupError): return False
# an existing junction wins: its name is ASCII, so it survives any code page and any shell (bash reads our stdout as UTF-8,
# while Python writes it in the ANSI code page - a representable Chinese path still arrives garbled under cp936)
if os.path.exists(JUNCTION) and os.path.realpath(JUNCTION).lower() == os.path.realpath(real).lower():
    print(JUNCTION.replace(os.sep, '/')); sys.exit(0)
if representable(real) and real.isascii(): print(real.replace(os.sep, '/')); sys.exit(0)
if not os.path.exists(JUNCTION):
    subprocess.run(['cmd', '/c', 'mklink', '/J', JUNCTION, real], check=True, capture_output=True)
elif os.path.realpath(JUNCTION).lower() != os.path.realpath(real).lower():
    sys.exit(f'{JUNCTION} exists but does not point to {real}')
print(JUNCTION.replace(os.sep, '/'))
