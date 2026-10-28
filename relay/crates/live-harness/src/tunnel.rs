use std::io;
use std::net::IpAddr;
use std::time::{Duration, Instant};

use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::time::{sleep, timeout};

const PATIENCE: Duration = Duration::from_secs(5);
const SILENCE: Duration = Duration::from_secs(2);
const READY: Duration = Duration::from_secs(20);

/// A TCP echo server on loopback: the internet the relay's users reach.
pub async fn echo_server() -> u16 {
    echo_server_on(IpAddr::from([127, 0, 0, 1])).await
}

/// A TCP echo server on `address`, which must belong to this machine.
pub async fn echo_server_on(address: IpAddr) -> u16 {
    let listener = TcpListener::bind((address, 0)).await.unwrap();
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
        Self::open_to(proxy, "127.0.0.1", target).await
    }

    /// Like [`Tunnel::open`], naming the target by `host`.
    pub async fn open_to(proxy: u16, host: &str, target: u16) -> io::Result<Self> {
        let stream = within(TcpStream::connect(("127.0.0.1", proxy))).await?;
        let mut stream = BufReader::new(stream);
        let connect = format!("CONNECT {host}:{target} HTTP/1.1\r\nHost: {host}:{target}\r\n\r\n");
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

    /// Sends `request` and reads until the far side closes. A target the relay blocks gives
    /// back no bytes.
    pub async fn exchange(mut self, request: &[u8]) -> io::Result<Vec<u8>> {
        self.0.get_mut().write_all(request).await?;
        let mut reply = Vec::new();
        within(self.0.read_to_end(&mut reply)).await?;
        Ok(reply)
    }

    /// Sends `request` and returns the first bytes the far side answers with. A target the
    /// relay blocks, or one that stays silent, gives back none.
    pub async fn first_reply(mut self, request: &[u8]) -> io::Result<Vec<u8>> {
        self.0.get_mut().write_all(request).await?;
        let mut reply = [0; 512];
        let read = timeout(SILENCE, self.0.read(&mut reply)).await;
        Ok(match read {
            Ok(Ok(count)) => reply[..count].to_vec(),
            _ => Vec::new(),
        })
    }

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

pub async fn roundtrip(proxy: u16, target: u16, payload: &[u8]) -> io::Result<()> {
    Tunnel::open(proxy, target).await?.echo(payload).await
}

/// Like [`roundtrip`], naming the target by `host`.
pub async fn roundtrip_to(proxy: u16, host: &str, target: u16, payload: &[u8]) -> io::Result<()> {
    Tunnel::open_to(proxy, host, target)
        .await?
        .echo(payload)
        .await
}

/// Like [`roundtrip_to`], retried until it succeeds or [`READY`] runs out, for a path that
/// is expected to work but may still be warming up.
pub async fn roundtrip_until(
    proxy: u16,
    host: &str,
    target: u16,
    payload: &[u8],
) -> io::Result<()> {
    let deadline = Instant::now() + READY;
    loop {
        match roundtrip_to(proxy, host, target, payload).await {
            Err(error) if Instant::now() >= deadline => return Err(error),
            Err(_) => sleep(Duration::from_millis(250)).await,
            Ok(()) => return Ok(()),
        }
    }
}

async fn within<T>(io: impl Future<Output = io::Result<T>>) -> io::Result<T> {
    timeout(PATIENCE, io)
        .await
        .unwrap_or_else(|_| Err(io::ErrorKind::TimedOut.into()))
}
