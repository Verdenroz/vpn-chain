use std::io;
use std::time::Duration;

use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::time::timeout;

const PATIENCE: Duration = Duration::from_secs(5);

/// A TCP echo server on loopback: the internet the relay's users reach.
pub async fn echo_server() -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        while let Ok((mut stream, _)) = listener.accept().await {
            tokio::spawn(async move {
                let (mut read, mut write) = stream.split();
                let _ = tokio::io::copy(&mut read, &mut write).await;
            });
        }
    });
    port
}

/// One connection through a sing-box client's mixed inbound to the echo server.
pub struct Tunnel(BufReader<TcpStream>);

impl Tunnel {
    pub async fn open(proxy: u16, target: u16) -> io::Result<Self> {
        let stream = within(TcpStream::connect(("127.0.0.1", proxy))).await?;
        let mut stream = BufReader::new(stream);
        let connect =
            format!("CONNECT 127.0.0.1:{target} HTTP/1.1\r\nHost: 127.0.0.1:{target}\r\n\r\n");
        stream.get_mut().write_all(connect.as_bytes()).await?;
        let mut status = String::new();
        within(stream.read_line(&mut status)).await?;
        if !status.contains(" 200 ") {
            return Err(io::Error::other(format!(
                "proxy refused CONNECT: {status:?}"
            )));
        }
        loop {
            let mut line = String::new();
            within(stream.read_line(&mut line)).await?;
            if line == "\r\n" || line.is_empty() {
                break;
            }
        }
        Ok(Self(stream))
    }

    /// Sends `payload` and waits for the relay to carry it back.
    pub async fn echo(&mut self, payload: &[u8]) -> io::Result<()> {
        self.0.get_mut().write_all(payload).await?;
        let mut back = vec![0; payload.len()];
        within(self.0.read_exact(&mut back)).await?;
        if back == payload {
            Ok(())
        } else {
            Err(io::Error::other("echo came back altered"))
        }
    }
}

/// Opens a fresh tunnel and echoes `payload` through it.
pub async fn roundtrip(proxy: u16, target: u16, payload: &[u8]) -> io::Result<()> {
    Tunnel::open(proxy, target).await?.echo(payload).await
}

async fn within<T>(io: impl Future<Output = io::Result<T>>) -> io::Result<T> {
    timeout(PATIENCE, io)
        .await
        .unwrap_or_else(|_| Err(io::ErrorKind::TimedOut.into()))
}
