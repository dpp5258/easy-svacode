package com.ruoyi.waring.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class WvpServer {

    private Long id;

    private String name;

    private String host;

    private Integer api_port;

    private String username;

    private String password;

    private Integer enabled;

    private java.util.Date create_time;

    private java.util.Date update_time;
}
