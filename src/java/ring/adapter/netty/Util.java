package board_fly.ring.adapter.netty;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.channel.ChannelFutureListener.CLOSE;
import static io.netty.handler.codec.http.HttpHeaders.setContentLength;
import static io.netty.util.CharsetUtil.UTF_8;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedStream;

import clojure.lang.ISeq;
import clojure.lang.Seqable;

//SimpleDateFormat is not threadsafe
class DateFormater extends ThreadLocal<SimpleDateFormat> {
    protected SimpleDateFormat initialValue() {
        // Formats into HTTP date format (RFC 822/1123).
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f;
    }
}

public class Util {

    private static final DateFormater FORMATER = new DateFormater();

    public static String getDate() {
        return FORMATER.get().format(new Date());
    }

    private static DefaultFullHttpResponse copyAndSetContent(DefaultHttpResponse resp, ByteBuf buffer) {
        DefaultFullHttpResponse fullResp = 
            new DefaultFullHttpResponse(
                    resp.getProtocolVersion(), resp.getStatus(), buffer);
        fullResp.headers().set(resp.headers());
        //fullResp.trailingHeaders().set(resp.trailingHeaders());
        return fullResp;
    }

    public static void writeResp(ChannelHandlerContext ctx, DefaultHttpResponse _resp,
            Object body, boolean keepAlive) throws IOException {
        final Channel ch = ctx.channel();
        if (body instanceof String) {
            final ByteBuf buffer = copiedBuffer((String) body, UTF_8).slice();
            // TODO: 增加内存消耗, 绕过setContent这API变更, 可以调整代码结构解决
            //resp.setContent(buffer);
            DefaultFullHttpResponse fullResp = copyAndSetContent(_resp, buffer);
            if (keepAlive) {
                System.out.println(body);
                System.out.println(buffer.readableBytes());
                setContentLength(fullResp, buffer.readableBytes());
                ch.writeAndFlush(fullResp);
                // TODO: 老版本全部没有flush, 我也不知道为啥
                //ch.write(fullResp);
            } else {
                ch.writeAndFlush(fullResp).addListener(CLOSE);
            }
        } else if (body instanceof Seqable) {
            //final List<ByteBuf> comps = new ArrayList<ByteBuf>();
            final List<ByteBuf> comps = new ArrayList<ByteBuf>();
            ISeq seq = ((Seqable) body).seq();
            int compsCount = 0;
            while (seq != null) {
                comps.add(copiedBuffer(seq.first().toString(), UTF_8).slice());
                seq = seq.next();
                compsCount++;
            }
            //ByteBuf buffer = new CompositeByteBuf(ByteOrder.BIG_ENDIAN, comps, false);
            ByteBuf buffer = new CompositeByteBuf(UnpooledByteBufAllocator.DEFAULT, false, compsCount, comps);

            // TODO: 增加内存消耗, 绕过setContent这API变更, 可以调整代码结构解决
            //resp.setContent(buffer);
            DefaultFullHttpResponse fullResp = copyAndSetContent(_resp, buffer);
            if (keepAlive) {
                setContentLength(fullResp, buffer.readableBytes());
                ch.writeAndFlush(fullResp);
            } else {
                ch.writeAndFlush(fullResp).addListener(CLOSE);
            }

        } else if (body instanceof File) {
            ch.writeAndFlush(_resp);
            final ChunkedFile f = new ChunkedFile((File) body);
            if (keepAlive) {
                ch.writeAndFlush(f);
            } else {
                ch.writeAndFlush(f).addListener(CLOSE);
            }
        } else if (body instanceof InputStream) {
            ch.writeAndFlush(_resp);
            final InputStream is = (InputStream) body;
            ch.writeAndFlush(new ChunkedStream(is)).addListener(new ChannelFutureListener() {
                public void operationComplete(ChannelFuture future) throws Exception {
                    future.channel().close();
                    is.close();
                }
            });

        } else if (body == null) {
            setContentLength(_resp, 0);
            if (keepAlive) {
                ch.writeAndFlush(_resp);
            } else {
                ch.writeAndFlush(_resp).addListener(CLOSE);
            }
        } else if (body instanceof HttpResponse) {
            if (keepAlive) {
                ch.writeAndFlush(body);
            } else {
                ch.writeAndFlush(body).addListener(CLOSE);
            }
        } else {
            throw new RuntimeException("Unrecognized body: " + body);
        }
    }
}
