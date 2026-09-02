package com.stockapp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.stockapp.common.constant.RedisKeys;
import com.stockapp.common.exception.BizException;
import com.stockapp.common.result.ErrorCode;
import com.stockapp.common.vo.DetailBootstrapVO;
import com.stockapp.common.vo.IntradayVO;
import com.stockapp.common.vo.PageResult;
import com.stockapp.common.vo.QuoteVO;
import com.stockapp.common.vo.StockVO;
import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.mapper.StockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 股票基础信息：搜索 / 列表 / 详情 */
@Service
@RequiredArgsConstructor
public class StockService {

    /** 「全部股票」分页接口的单页上限，防止绕过前端直接传超大 size 打满一次查询 */
    private static final int MAX_PAGE_SIZE = 100;

    private final StockMapper stockMapper;
    private final MarketService marketService;
    private final RedisCacheHelper cache;

    /** 按代码或名称模糊搜索（code/name 均有索引），本身已有 LIMIT 20，量级与股票池大小无关 */
    public List<StockVO> search(String keyword) {
        LambdaQueryWrapper<Stock> qw = new LambdaQueryWrapper<Stock>()
                .eq(Stock::getStatus, 1)
                .and(w -> w.likeRight(Stock::getCode, keyword)
                        .or().like(Stock::getName, keyword))
                .last("LIMIT 20");
        return withQuote(stockMapper.selectList(qw));
    }

    /**
     * 全量股票列表（内部用途：热门榜单排序、K线预热选股范围等genuinely需要
     * 拿到全集的场景）。这里不分页是因为调用方本来就要处理全集；真正暴露给
     * 前端"浏览全部股票"的是下面的 {@link #listPage}。
     * withQuote 内部已经是批量取行情，即使全量调用也不会退化成 N 次串行 Redis。
     */
    public List<StockVO> listAll() {
        return withQuote(stockMapper.selectList(
                new LambdaQueryWrapper<Stock>().eq(Stock::getStatus, 1)
                        .orderByAsc(Stock::getId)));
    }

    /**
     * 分页股票列表：GET /api/stocks 的实现，前端"全部股票"长列表用这个。
     * 股票池多大，单次响应体和查询开销都固定在 size，不会随总数线性增长。
     */
    public PageResult<StockVO> listPage(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<Stock> result = stockMapper.selectPage(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<Stock>().eq(Stock::getStatus, 1).orderByAsc(Stock::getId));
        return PageResult.of(withQuote(result.getRecords()), result.getTotal(), safePage, safeSize);
    }

    /**
     * 按代码取股票基础信息。
     *
     * 详情页冷启动链路（bootstrap / kline / indicators / news）每个请求都要
     * 先过一次 getByCode 做存在性校验，等于每次页面打开要打 3-4 次同样的
     * 单行 SELECT。基础信息几乎不变，这里启用 RedisKeys.INFO（6 小时 TTL，
     * 该 key 此前已定义但一直未接线）做 cache-aside：
     * - 「不存在」不会被缓存：loader 抛 BizException 时直接向上传播，不写缓存；
     * - Redis 故障时 RedisCacheHelper 自动降级为直查 DB，行为与之前完全一致。
     */
    public Stock getByCode(String code) {
        return cache.getOrLoad(RedisKeys.info(code), RedisKeys.INFO_TTL,
                new TypeReference<Stock>() {},
                () -> loadByCode(code));
    }

    private Stock loadByCode(String code) {
        Stock stock = stockMapper.selectOne(
                new LambdaQueryWrapper<Stock>().eq(Stock::getCode, code));
        if (stock == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }
        return stock;
    }

    /** 批量按 id 取股票，返回 id -> Stock 映射（供持仓/成交列表避免 N+1 查询） */
    public java.util.Map<Long, Stock> mapByIds(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return java.util.Map.of();
        }
        return stockMapper.selectBatchIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(Stock::getId, x -> x));
    }

    public Stock getById(Long id) {
        Stock stock = stockMapper.selectById(id);
        if (stock == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }
        return stock;
    }

    public StockVO toVO(Stock stock) {
        QuoteVO q = marketService.getQuote(stock.getCode(), stock.getName());
        return toVO(stock, q);
    }

    /** 已持有行情时复用，避免同一请求内重复取一次 quote（供 detail-bootstrap 使用） */
    public StockVO toVO(Stock stock, QuoteVO q) {
        return StockVO.builder()
                .id(stock.getId()).code(stock.getCode()).name(stock.getName())
                .market(stock.getMarket()).industry(stock.getIndustry())
                .price(q.getPrice())
                .changeAmount(q.getChangeAmount())
                .changePercent(q.getChangePercent())
                .build();
    }

    /**
     * 按代码批量取实时行情：可视区高频轮询专用（前端 visiblePricePolling.ts）。
     * 跟 withQuote 的场景差异——withQuote 是"我已经查出一批 Stock 实体，
     * 顺带把行情补上"；这里反过来，调用方只有 code 列表（当前屏幕可见的
     * 股票），先按 code 批量查基础信息拿到 name（marketService.getQuotes
     * 的 mock 数据源要用），再一次批量取行情。
     *
     * MAX_BATCH_CODES 限流：这是 permitAll 的公开接口，不加上限的话
     * ?codes=A,B,C...（几千个）会绕过"一屏最多几十只"这个前端假设，
     * 直接查数据库+打满 Redis 批量指令。正常一屏可见行数远低于这个上限，
     * 不影响真实使用。
     */
    private static final int MAX_BATCH_CODES = 50;

    public List<QuoteVO> getQuotesByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        List<String> distinct = codes.stream().distinct().limit(MAX_BATCH_CODES).toList();
        List<Stock> stocks = stockMapper.selectList(
                new LambdaQueryWrapper<Stock>().in(Stock::getCode, distinct));
        if (stocks.isEmpty()) {
            return List.of();
        }
        Map<String, String> nameByCode = stocks.stream()
                .collect(Collectors.toMap(Stock::getCode, Stock::getName, (a, b) -> a));
        Map<String, QuoteVO> quotes = marketService.getQuotes(nameByCode);
        // 按传入顺序返回，跳过库里查不到的 code（比如已下架），不报错——
        // 轮询场景下，某几只股票这一轮没取到，不该影响其余股票正常更新
        return distinct.stream().map(quotes::get).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * 批量详情页首屏聚合：POST /api/stocks/detail-bootstrap/batch 的实现，
     * 供前端视口预取把同一屏内新进入视口的多只股票打包成一次请求（见前端
     * viewportPrefetch.ts），替代原来"进入视口的每只股票各发一次
     * detail-bootstrap"的做法。
     *
     * 上限 13 跟前端视口预取 LRU 队列容量对齐（该队列本来就不会攒出更多待
     * 发起的 code），这里的 limit 是给接口自身的防御，不依赖调用方守规矩。
     *
     * 内部分别走 stock / quote / intraday 各自已有的批量 cache-aside（一次
     * MGET + 未命中并行回源），而不是循环调用单只版本退化成 3×N 次
     * Redis/DB 往返。查无此股票的 code 直接从结果里跳过、不报错——前端
     * 按 code 取不到时会各自降级为单独请求重试（见 detailPrefetch.ts），
     * 不该因为批次里一只股票不存在就让整批失败。
     */
    private static final int MAX_BOOTSTRAP_BATCH_CODES = 13;

    public Map<String, DetailBootstrapVO> detailBootstrapBatch(List<String> codes) {
        List<String> distinct = codes.stream().distinct().limit(MAX_BOOTSTRAP_BATCH_CODES).toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<Stock> stocks = stockMapper.selectList(
                new LambdaQueryWrapper<Stock>().in(Stock::getCode, distinct));
        if (stocks.isEmpty()) {
            return Map.of();
        }
        Map<String, String> nameByCode = stocks.stream()
                .collect(Collectors.toMap(Stock::getCode, Stock::getName, (a, b) -> a));
        List<String> foundCodes = new ArrayList<>(nameByCode.keySet());
        Map<String, QuoteVO> quotes = marketService.getQuotes(nameByCode);
        Map<String, IntradayVO> intradays = marketService.getIntradayBatch(foundCodes);

        Map<String, DetailBootstrapVO> result = new LinkedHashMap<>();
        for (Stock s : stocks) {
            QuoteVO q = quotes.get(s.getCode());
            if (q == null) {
                q = marketService.getQuote(s.getCode(), s.getName()); // 批量回源极少数单只失败时兜底
            }
            IntradayVO intraday = intradays.get(s.getCode());
            if (intraday == null) {
                intraday = marketService.getIntraday(s.getCode());
            }
            result.put(s.getCode(), DetailBootstrapVO.builder()
                    .stock(toVO(s, q))
                    .quote(q)
                    .intraday(intraday)
                    .build());
        }
        return result;
    }

    /**
     * 批量补充行情：改用 marketService.getQuotes 一次性取一批，替代原来
     * 每只股票单独调 toVO -> marketService.getQuote 串行打 N 次 Redis。
     * 8 只股票感觉不到差别，几千只时这一处就是几秒延迟的直接来源。
     */
    private List<StockVO> withQuote(List<Stock> stocks) {
        if (stocks.isEmpty()) {
            return List.of();
        }
        Map<String, String> nameByCode = stocks.stream()
                .collect(Collectors.toMap(Stock::getCode, Stock::getName, (a, b) -> a));
        Map<String, QuoteVO> quotes = marketService.getQuotes(nameByCode);
        return stocks.stream()
                .map(s -> {
                    QuoteVO q = quotes.get(s.getCode());
                    // 批量回源里极少数单只失败时的兜底，不影响其余股票正常返回
                    if (q == null) {
                        q = marketService.getQuote(s.getCode(), s.getName());
                    }
                    return toVO(s, q);
                })
                .toList();
    }
}
