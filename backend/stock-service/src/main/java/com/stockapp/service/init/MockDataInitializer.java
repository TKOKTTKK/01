package com.stockapp.service.init;

import com.stockapp.dao.entity.Stock;
import com.stockapp.dao.mapper.StockMapper;
import com.stockapp.service.market.MarketDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时初始化 Mock 数据（幂等）：为每只股票生成 K 线 + 新闻。
 * 已存在数据的股票自动跳过，不会产生重复（另有数据库唯一约束兜底）。
 *
 * 【v3.2 修复：事务粒度从"整个股票池"收窄到"单只股票"】原来本类自己
 * 在 run()/catchUpAll() 上加 @Transactional，把全部股票的补齐放进一个
 * 大事务、逐行 insert——股票池小的时候（几只到几十只）无所谓，扩到几百
 * 上千只后，一个事务里塞几万到十几万行、几万到十几万次串行 DB 往返，
 * 启动阶段极易跑不完就被打断（连接超时/部署平台健康检查超时杀进程）。
 * 一旦打断，整个事务回滚，这一批新股票一行数据都留不下，表现为
 * "老股票能正常打开、新股票 K 线全部报错"——老股票是很久以前独立成功
 * 提交的，跟这次的大事务无关；新股票每次重启都要重新陪绑这个必然
 * 超时的大事务，永远也补不上。
 *
 * 现在改为：本类只负责编排循环，真正的写库逻辑（含 @Transactional 和
 * 批量插入）收敛到 {@link MockDataSyncService#syncStock}，每只股票各自
 * 独立一个事务——单只失败（日志记录并跳过）不会牵连其余股票。
 *
 * v3.1：补齐逻辑抽出为公开的 {@link #catchUpAll()}，供 KlineWarmupJob
 * 在每日跨天后调用——之前只有重启才会补新一根日 K，长期在线的实例
 * 会出现「分时昨收在走、DB K 线停在部署当天」的漂移；现在跨天由定时任务
 * 主动补齐，K 线缓存（按日期分 key）随之立即命中新数据。
 * {@code @Order(1)} 保证本初始化先于 KlineWarmupJob（@Order(2)）执行，
 * 预热时缓存里装的一定是补齐后的数据。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class MockDataInitializer implements ApplicationRunner {

    private final StockMapper stockMapper;
    private final MockDataSyncService syncService;
    private final MarketDataProvider provider;

    @Override
    public void run(ApplicationArguments args) {
        catchUpAll();
    }

    /**
     * 对全部股票补齐 K 线 + 初始化新闻（幂等，可重复调用）。
     *
     * synchronized：启动 Runner 与定时任务可能并发触发（极端时序下），
     * 补齐涉及"删旧周K再插"两步写入，串行化最省心；正常情况下锁无竞争。
     * 注意这里不再有 @Transactional——事务边界在 syncStock 内部，
     * 一只股票一个事务，本方法只是编排循环。
     */
    public synchronized void catchUpAll() {
        if (!provider.isMock()) {
            log.info("当前数据源为 {}，跳过 Mock 数据初始化", provider.name());
            return;
        }
        List<Stock> stocks = stockMapper.selectList(null);
        int ok = 0;
        for (Stock stock : stocks) {
            try {
                syncService.syncStock(stock);
                ok++;
            } catch (Exception e) {
                log.error("Mock 数据补齐失败: code={}, name={}, err={}",
                        stock.getCode(), stock.getName(), e.getMessage());
            }
        }
        log.info("Mock 数据初始化/补齐完成，{}/{} 只股票成功", ok, stocks.size());
    }
}
