package com.ruoyi.waring.mapper;

import com.ruoyi.waring.domain.WvpServer;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface WvpServerMapper {

    WvpServer selectEnabledById(Long id);

    List<WvpServer> selectWvpServerList(WvpServer query);

    WvpServer selectWvpServerById(Long id);

    int insertWvpServer(WvpServer w);

    int updateWvpServer(WvpServer w);

    int deleteWvpServerById(Long id);

    int countByName(WvpServer w);
}
