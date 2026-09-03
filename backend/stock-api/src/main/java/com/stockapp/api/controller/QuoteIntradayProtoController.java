package com.stockapp.api.controller;

import com.stockapp.api.proto.QuoteIntradayProtoMapper;
import com.stockapp.common.proto.QuoteIntradayResponse;
import com.stockapp.common.proto.QuoteIntradayVO;
import com.stockapp.common.vo.IntradayVO;
import com.stockapp.common.vo.QuoteVO;
import com.stockapp.dao.entity.Stock;
import com.stockapp.service.MarketService;
import com.stockapp.service.StockService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/**
 * 行情 + 分时的 Protobuf 二进制接口（Protobuf + Gzip 重构 POC）。
 *
 * 跟 {@link StockController} 里原有的 JSON 版 /quote、/intraday 是并存关系，
 * 不是替换：老接口继续给调试面板、还没升级的调用方用；这个新接口单独挂
 * 一个 .pb 后缀路径，用 Accept-Encoding 协商是否要 gzip，方便前端灰度切换、
 * 也方便直接用浏览器/Postman 对比两种协议下的实际字节数差异。
 *
 * 没有走 Spring 的 HttpMessageConverter 自动转换，而是拿到 HttpServletResponse
 * 直接手写字节流——这样能显式控制“先序列化成 Protobuf 二进制、再决定要不要
 * gzip、最后设置哪些响应头”这一整条链路，跟需求里“手动设置 Content-Type /
 * Content-Encoding”的要求对应得更直接。
 */
@Slf4j
@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class QuoteIntradayProtoController {

    private static final String CONTENT_TYPE_PROTOBUF = "application/x-protobuf";

    /** 低于这个字节数不值得再花 CPU 去 gzip——头部开销可能反而让结果更大。
     *  1KB 是业界常见的经验阈值（Spring Boot 自己的 server.compression.min-response-size
     *  默认值也是 2048，这里取更保守的下限）。 */
    private static final int GZIP_MIN_SIZE = 1024;

    private final StockService stockService;
    private final MarketService marketService;

    @GetMapping(value = "/{code}/quote-intraday.pb")
    public void quoteIntradayProto(@PathVariable String code,
                                    HttpServletRequest request,
                                    HttpServletResponse response) throws IOException {
        Stock stock = stockService.getByCode(code);
        QuoteVO quote = marketService.getQuote(stock.getCode(), stock.getName());
        IntradayVO intraday = marketService.getIntraday(stock.getCode());

        QuoteIntradayVO data = QuoteIntradayProtoMapper.toProto(quote, intraday);
        QuoteIntradayResponse resp = QuoteIntradayResponse.newBuilder()
                .setCode(0)
                .setMessage("success")
                .setData(data)
                .build();

        writeProtoResponse(request, response, resp.toByteArray());
    }

    /**
     * 把序列化好的 Protobuf 字节按需 gzip 后写回，并设置正确的响应头。
     *
     * 关键点：
     * 1. Content-Type 固定 application/x-protobuf——不是 application/json，
     *    客户端/代理都不应该尝试把它当文本解析。
     * 2. 只有请求方 Accept-Encoding 里明确带了 gzip，才压缩并回
     *    Content-Encoding: gzip；否则原样返回未压缩字节。不能无条件压缩，
     *    否则遇到不支持解压的调用方（比如某些直接拿 body 落盘的内部工具）
     *    会拿到一坨读不懂的乱码。
     * 3. Vary: Accept-Encoding——告诉中间代理/CDN，同一个 URL 在不同
     *    Accept-Encoding 下响应体不同，不能用同一份缓存回给所有客户端。
     * 4. 手写响应意味着绕开了 Spring 的自动 gzip（本项目 application.yml 里
     *    server.compression 的 mime-types 也没加 application/x-protobuf，
     *    两边不会打架、不会出现被压缩两次的问题）。
     */
    private void writeProtoResponse(HttpServletRequest request, HttpServletResponse response,
                                     byte[] rawBytes) throws IOException {
        String acceptEncoding = request.getHeader("Accept-Encoding");
        boolean clientAcceptsGzip = acceptEncoding != null && acceptEncoding.toLowerCase().contains("gzip");

        response.setContentType(CONTENT_TYPE_PROTOBUF);
        response.setHeader("Vary", "Accept-Encoding");

        if (clientAcceptsGzip && rawBytes.length >= GZIP_MIN_SIZE) {
            byte[] gzipped = gzip(rawBytes);
            // 极端情况下（数据本身接近随机、重复模式少）gzip 后可能不比原始小，
            // 这时候直接传原始字节反而更省——不要为了“用了压缩”而压缩。
            if (gzipped.length < rawBytes.length) {
                response.setHeader("Content-Encoding", "gzip");
                response.setContentLength(gzipped.length);
                response.getOutputStream().write(gzipped);
                response.getOutputStream().flush();
                return;
            }
        }
        response.setContentLength(rawBytes.length);
        response.getOutputStream().write(rawBytes);
        response.getOutputStream().flush();
    }

    private byte[] gzip(byte[] input) throws IOException {
        // ByteArrayOutputStream 会把整份压缩结果留在堆内存里，
        // 这里数据量级（单只股票行情+240 个分时点，压缩前几 KB）完全没问题；
        // 如果以后要扩展成“全市场批量快照”这种量级，就不能再这样一次性
        // 攒进内存，得改成流式压缩直接往 response 的 OutputStream 里写
        // （用 GZIPOutputStream 包裹 response.getOutputStream()），详见
        // 本轮回复里“重构注意事项”第 1 条。
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, input.length / 2));
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(input);
        }
        return baos.toByteArray();
    }
}
