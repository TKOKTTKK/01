package com.stockapp.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockapp.dao.entity.StockKline;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockKlineMapper extends BaseMapper<StockKline> {

    /**
     * 批量插入 K 线，一条多值 INSERT 搞定一批，重复（同一 stock_id +
     * period_type + trade_date，命中 uk_kline 唯一约束）直接 DO NOTHING 跳过。
     *
     * 【为什么需要这个】原来是 MockDataInitializer 里 for 循环逐条 insert，
     * 股票池小的时候无所谓，几百只股票 × 250+根K线就是几万到十几万次
     * 串行 DB 往返，启动阶段极易超时/被打断。调用方按分片传入（见
     * MockDataSyncService 的 CHUNK_SIZE），避免单条 SQL 绑定参数数量顶到
     * PostgreSQL 65535 个参数的上限（本表 10 列，留足安全余量）。
     */
    @Insert("""
        <script>
        INSERT INTO stock_kline
            (stock_id, period_type, trade_date, open_price, high_price, low_price,
             close_price, volume, amount, created_at)
        VALUES
        <foreach collection="rows" item="r" separator=",">
            (#{r.stockId}, #{r.periodType}, #{r.tradeDate}, #{r.openPrice}, #{r.highPrice}, #{r.lowPrice},
             #{r.closePrice}, #{r.volume}, #{r.amount}, #{r.createdAt})
        </foreach>
        ON CONFLICT (stock_id, period_type, trade_date) DO NOTHING
        </script>
        """)
    int insertBatch(@Param("rows") List<StockKline> rows);
}
