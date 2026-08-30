package com.stockapp.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockapp.dao.entity.StockNews;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StockNewsMapper extends BaseMapper<StockNews> {
}
