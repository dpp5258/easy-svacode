package com.ruoyi.waring.mapper;

import java.util.List;

import com.ruoyi.waring.domain.WvpServer;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface WvpServerMapper {

    WvpServer selectEnabledById(Long id);

    List<WvpServer> selectWvpServerList(WvpServer wvp);

    WvpServer selectWvpServerById(Long id);

    int insertWvpServer(WvpServer wvp);

    int updateWvpServer(WvpServer wvp);

    int deleteWvpServerById(Long id);
}
