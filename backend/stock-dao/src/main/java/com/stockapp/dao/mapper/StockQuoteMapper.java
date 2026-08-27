package com.stockapp.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockapp.dao.entity.StockQuote;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockQuoteMapper extends BaseMapper<StockQuote> {

    /**
     * 批量插入行情快照，一条多值 INSERT 搞定一批，重复（同一 stock_id +
     * trade_time，命中 uk_quote_stock_time 唯一约束）直接 DO NOTHING 跳过。
     *
     * 【为什么需要这个】原来是 for 循环逐条 insert + 捕获 DuplicateKeyException，
     * 股票池小的时候无所谓，几千只股票每分钟一次快照就是几千次串行 DB 往返。
     * 一条多值 INSERT 把往返次数压到 1 次；调用方按分片传入（见 QuoteSnapshotJob
     * 的 CHUNK_SIZE），避免单条 SQL 绑定参数数量顶到 PostgreSQL 65535 个参数的
     * 上限（本表 12 列，理论上一条 SQL 最多能塞约 5460 行，分片留足安全余量）。
     */
    @Insert("""
        <script>
        INSERT INTO stock_quote
            (stock_id, price, open_price, high_price, low_price, pre_close,
             change_amount, change_percent, volume, amount, trade_time, created_at)
        VALUES
        <foreach collection="rows" item="r" separator=",">
            (#{r.stockId}, #{r.price}, #{r.openPrice}, #{r.highPrice}, #{r.lowPrice}, #{r.preClose},
             #{r.changeAmount}, #{r.changePercent}, #{r.volume}, #{r.amount}, #{r.tradeTime}, #{r.createdAt})
        </foreach>
        ON CONFLICT (stock_id, trade_time) DO NOTHING
        </script>
        """)
    int insertBatch(@Param("rows") List<StockQuote> rows);
}
