# 说明

## 基础架构

本工具需要配合使用 [jHipster](http://www.jhipster.tech) 进行内容的生成。
并且会在 [JDL](http://www.jhipster.tech/jdl/) 的实体定义中加入一些控制标签，
实现对代码的二次修改和处理。

工具会对 jHipster 的代码进行重新整理，并提取存放到新结构中。新结构将会分成很多
新的模块工程目录，按照模块功能进行拆分。

每一个模块工程都是带界面和后台代码的独立部分，一般包含多个project，
能够适应微服务方式的部署。

## 控制标签

* pg-extends: XXX, pg-implements: XXX

    XXX 指定实体的基类或者接口实现，其中的XXX格式定义为 value\[@\[限定,限定,...\]\]
* pg-@XXX(xxx) 

    附加实体一些annotation
* pg-state: 

    手工属性，默认全部都要。取值范围为：
    none,create,delete,edit,detail,list, list-edit
* pg-entity: YYY 当前对象是 YYY 对象的简化， 手工属性。

    处理时会将当前对象复制到 YYY 的目录下并改名
* pg-map-to: YYY   

    映射view对象到YYY对象的BO，手工属性
* pg-view：XXX,YYY, ... 

    帮助在 app/views/下生成特定的视图，里边包含 directive 组合的页面，手工属性
* pg-relationship-XXX: 

    当前对象和 XXX 实体的关联关系，自动生成属性
* pg-relation-dir-XXX: 

    当前对象和 XXx 实体关系的关联方向，自动生成属性






