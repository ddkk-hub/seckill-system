"""Minimal local RESP2 client for experiment monitoring; no third-party Redis dependency."""
import os
import socket

class Redis:
    def __enter__(self):
        self.socket = socket.create_connection((os.getenv('REDIS_HOST','127.0.0.1'),int(os.getenv('REDIS_PORT','6379'))),3)
        self.stream = self.socket.makefile('rb')
        if os.getenv('REDIS_PASSWORD'): self.call('AUTH',os.environ['REDIS_PASSWORD'])
        self.call('SELECT',os.getenv('REDIS_DATABASE','0'))
        return self
    def __exit__(self,*args):
        self.stream.close();self.socket.close()
    def call(self,*args):
        values=[str(x).encode('utf-8') for x in args]
        self.socket.sendall(b'*'+str(len(values)).encode()+b'\r\n'+b''.join(b'$'+str(len(v)).encode()+b'\r\n'+v+b'\r\n' for v in values))
        return self.read()
    def read(self):
        prefix=self.stream.read(1);line=self.stream.readline().rstrip(b'\r\n')
        if prefix==b'+':return line.decode()
        if prefix==b'-':raise RuntimeError(line.decode())
        if prefix==b':':return int(line)
        if prefix==b'$':
            size=int(line)
            if size<0:return None
            data=self.stream.read(size);self.stream.read(2);return data.decode('utf-8')
        if prefix==b'*':return [self.read() for _ in range(int(line))]
        raise RuntimeError('Unexpected Redis response')
    def info(self):
        return dict(line.split(':',1) for line in self.call('INFO').splitlines() if ':' in line and not line.startswith('#'))
