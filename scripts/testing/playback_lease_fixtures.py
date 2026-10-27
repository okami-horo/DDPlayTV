"""Loopback-only wire fixtures for PlaybackTransportProtocolIntegrationTest.

Requires impacket 0.13.0 and pyftpdlib 2.1.0 in a separate test environment.
Run: python scripts/testing/playback_lease_fixtures.py smb|ftp <fixture-directory>
"""

import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("protocol", choices=("smb", "ftp"))
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    root = args.directory.resolve()
    root.mkdir(parents=True, exist_ok=True)
    (root / "probe.bin").write_bytes(bytes((i * 31 + 7) % 251 for i in range(256 * 1024)))

    if args.protocol == "smb":
        from impacket.ntlm import compute_lmhash, compute_nthash
        from impacket.smbserver import SimpleSMBServer

        server = SimpleSMBServer(listenAddress="127.0.0.1", listenPort=1445)
        server.setSMB2Support(True)
        server.addShare("videos", str(root), readOnly="yes")
        server.addCredential("lease", 0, compute_lmhash("lease-fixture"), compute_nthash("lease-fixture"))
        server.setLogFile(str(root / "smb-fixture.log"))
        print("SMB fixture ready on 127.0.0.1:1445", flush=True)
        server.start()
    else:
        from pyftpdlib.authorizers import DummyAuthorizer
        from pyftpdlib.handlers import FTPHandler
        from pyftpdlib.servers import FTPServer

        authorizer = DummyAuthorizer()
        authorizer.add_user("lease", "lease-fixture", str(root), perm="elr")
        class ListingFTPHandler(FTPHandler):
            def pre_process_command(self, line, cmd, arg):
                # Commons Net's existing hidden-file mode sends LIST -a <path>.
                # pyftpdlib accepts -a alone but needs this UNIX-style path form normalized.
                if cmd == "LIST" and arg.startswith("-a "):
                    arg = arg[3:]
                    line = "LIST " + arg
                return super().pre_process_command(line, cmd, arg)

        ListingFTPHandler.authorizer = authorizer
        print("FTP fixture ready on 127.0.0.1:2121", flush=True)
        FTPServer(("127.0.0.1", 2121), ListingFTPHandler).serve_forever()


if __name__ == "__main__":
    main()
