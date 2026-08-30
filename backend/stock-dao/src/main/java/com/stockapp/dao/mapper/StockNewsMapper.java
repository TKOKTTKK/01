package com.stockapp.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockapp.dao.entity.StockNews;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockNewsMapper extends BaseMapper<StockNews> {

    /** 批量插入新闻：把单只股票 8 条新闻的 8 次往返压成 1 次 */
    @Insert("""
        <script>
        INSERT INTO stock_news
            (stock_id, title, source, url, content, publish_time, created_at)
        VALUES
        <foreach collection="rows" item="r" separator=",">
            (#{r.stockId}, #{r.title}, #{r.source}, #{r.url}, #{r.content}, #{r.publishTime}, #{r.createdAt})
        </foreach>
        </script>
        """)
    int insertBatch(@Param("rows") List<StockNews> rows);
}
