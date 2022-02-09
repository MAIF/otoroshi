use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

use std::future::Future;
use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

#[derive(Debug)]
pub(super) struct CopyBuffer {
    read_done: bool,
    need_flush: bool,
    pos: usize,
    cap: usize,
    amt: u64,
    buf: Box<[u8]>,
}

impl CopyBuffer {
    pub(super) fn new() -> Self {
        Self {
            read_done: false,
            need_flush: false,
            pos: 0,
            cap: 0,
            amt: 0,
            buf: vec![0; 16 * 1024].into_boxed_slice(),
        }
    }

    pub(super) fn poll_copy<R, W>(
        &mut self,
        cx: &mut Context<'_>,
        mut reader: Pin<&mut R>,
        mut writer: Pin<&mut W>,
        client_cert: &str,
    ) -> Poll<io::Result<u64>>
    where
        R: AsyncRead + ?Sized,
        W: AsyncWrite + ?Sized,
    {
        loop {
            // If our buffer is empty, then we need to read some data to
            // continue.
            if self.pos == self.cap && !self.read_done {
                let me = &mut *self;
                let mut buf = ReadBuf::new(&mut me.buf);

                match reader.as_mut().poll_read(cx, &mut buf) {
                    Poll::Ready(Ok(_)) => (),
                    Poll::Ready(Err(err)) => return Poll::Ready(Err(err)),
                    Poll::Pending => {
                        // Try flushing when the reader has no progress to avoid deadlock
                        // when the reader depends on buffered writer.
                        if self.need_flush {
                            ready!(writer.as_mut().poll_flush(cx))?;
                            self.need_flush = false;
                        }

                        return Poll::Pending;
                    }
                }

                let n = buf.filled().len();
                if n == 0 {
                    self.read_done = true;
                } else {
                    self.pos = 0;
                    self.cap = n;
                }
            }

            let mut found = false;

            // If our buffer has some data, let's write it out!
            while self.pos < self.cap {
                let me = &mut *self;
                let mut i = 0;
                if !found { // injecting client certificate here !
                  let slice: &[u8] = &me.buf[me.pos..me.cap];
                  let slice_str = std::str::from_utf8(slice).unwrap();
                  // println!("slice: {:?}", slice_str);
                  let added = format!("X-Forwarded-Client-Cert-Chain: {}", client_cert);
                  if slice_str.contains("Host: ") {
                    let fmt_headers = format!("{}\r\nHost: ", added);
                    let new_chunk = slice_str.replace("Host: ", &fmt_headers[..]);
                    let new_chunk_bytes = new_chunk.as_bytes();
                    i = ready!(writer.as_mut().poll_write(cx, &new_chunk_bytes))?;
                    i = i - added.len() - 2;
                    found = true;
                    // println!("chunk : {:?}", new_chunk);
                  } else if slice_str.contains("host: ") {
                    let fmt_headers = format!("{}\r\nhost: ", added);
                    let new_chunk = slice_str.replace("host: ", &fmt_headers[..]);
                    i = ready!(writer.as_mut().poll_write(cx, &new_chunk.as_bytes()))?;
                    i = i - added.len() - 2;
                    found = true;
                  } else {
                    i = ready!(writer.as_mut().poll_write(cx, &me.buf[me.pos..me.cap]))?;
                  }
                  found = true; // first chunk should be the good one !
                } else {
                  i = ready!(writer.as_mut().poll_write(cx, &me.buf[me.pos..me.cap]))?;
                }
                if i == 0 {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::WriteZero,
                        "write zero byte into writer",
                    )));
                } else {
                    self.pos += i;        
                    self.amt += i as u64; 
                    self.need_flush = true;
                }
            }

            // If pos larger than cap, this loop will never stop.
            // In particular, user's wrong poll_write implementation returning
            // incorrect written length may lead to thread blocking.
            debug_assert!(
                self.pos <= self.cap,
                "writer returned length larger than input slice"
            );

            // If we've written all the data and we've seen EOF, flush out the
            // data and finish the transfer.
            if self.pos == self.cap && self.read_done {
                ready!(writer.as_mut().poll_flush(cx))?;
                return Poll::Ready(Ok(self.amt));
            }
        }
    }
}

#[derive(Debug)]
#[must_use = "futures do nothing unless you `.await` or poll them"]
struct Copy<'a, R: ?Sized, W: ?Sized> {
    reader: &'a mut R,
    writer: &'a mut W,
    client_cert: &'a str,
    buf: CopyBuffer,
}

pub async fn copy<'a, R, W>(reader: &'a mut R, writer: &'a mut W, client_cert: &str) -> io::Result<u64>
where
    R: AsyncRead + Unpin + ?Sized,
    W: AsyncWrite + Unpin + ?Sized,
{
    Copy {
        reader,
        writer,
        client_cert,
        buf: CopyBuffer::new()
    }.await
}

impl<R, W> Future for Copy<'_, R, W>
where
    R: AsyncRead + Unpin + ?Sized,
    W: AsyncWrite + Unpin + ?Sized,
{
    type Output = io::Result<u64>;

    fn poll(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<u64>> {
        let me = &mut *self;

        me.buf
            .poll_copy(cx, Pin::new(&mut *me.reader), Pin::new(&mut *me.writer), me.client_cert)
    }
}