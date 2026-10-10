package com.flinkweb.service.system;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 管理平台唯一的文件下载器镜像绑定。 */
public interface DownloaderSettingsService {
  /** 查询当前生效的绑定和修订号。 */
  ObjectNode get();

  /** 管理员以修订号更新唯一绑定。 */
  ObjectNode save(ObjectNode input);

  /** 获取生成部署所需的镜像，未配置时给出明确提示。 */
  String requireImage();
}
