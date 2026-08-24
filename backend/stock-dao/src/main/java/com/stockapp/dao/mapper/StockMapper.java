package com.stockapp.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockapp.dao.entity.Stock;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StockMapper extends BaseMapper<Stock> {
}
