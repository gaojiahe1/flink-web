# BES 构建依赖

此目录保存用户提供的原始 `bes-lite-spring-boot-starter-11.5.0.006.jar`，未修改二进制内容。
SHA-256：`b48de2e5f8ab0526cfa7b9c37a46ab0b9bc856864de57ddaa360f212c9605429`。

附件不含 Maven POM，因此本项目使用内部坐标
`com.flinkweb.vendor:bes-lite-spring-boot-starter:11.5.0.006` 和最小 POM 引用这个独立 bundle；
该坐标及 POM 是本项目的构建元数据，不宣称是厂商发布的依赖清单。

默认 Maven 构建从本目录的只读 file 仓库解析，不依赖开发者桌面路径或全局安装。
使用 `mirrorOf=*` 的内网 Maven 配置时，应将 `flink-web-vendor` 排除出镜像匹配，
或先把同一原始 JAR/POM 导入内网仓库/本地缓存；不要替换成版本不明的网络包。
离线构建需要先将本目录对应坐标的文件放入指定 Maven 本地仓库。

原始 bundle 内的厂商资源保持原样，不单独提取、复制或追加授权文件到仓库或知识文档；
如运行环境需要独立授权，按厂商约定从外部提供。
