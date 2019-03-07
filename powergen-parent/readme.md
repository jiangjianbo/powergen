# 说明

## 基础架构

本工具需要配合使用 [jHipster](http://www.jhipster.tech) 进行内容的生成。
并且会在 [JDL](http://www.jhipster.tech/jdl/) 的实体定义中加入一些控制标签，
实现对代码的二次修改和处理。

工具会对 jHipster 的代码进行重新整理，并提取存放到新结构中。新结构将会分成很多
新的模块工程目录，按照模块功能进行拆分。

每一个模块工程都是带界面和后台代码的独立部分，一般包含多个project，
能够适应微服务方式的部署。

## 核心逻辑

本工具的核心逻辑是设定界面（View Object， VO）、业务逻辑（Business Object， BO）
和存储（Persistent Object， PO）三层使用的数据结构是不一致的，但是是可以相互映射的，
且每个数据对象都有专门的传输层定义（即关联有 DTO 对象）。

在具体的最终程序运行过程，存在如下的几个层：

1. 界面层

    一个界面的数据尽量通过一次交互就能够获得。界面层的结构，将从 VO 的 DTO 转换成 BO的DTO，
    传递给业务层
2. 业务层

    业务层所有的 API 接口对外的交互都是通过 BO 的 DTO 对象，核心的 BO 对象不会暴露在外。
    真正的业务处理都是使用 BO 对象进行处理。运算完成之后，BO对象直接传递给存储层。也就是说，
    业务层是整个应用的内核，Domain对象（即BO）就是后台的通用语言。
3. 存储层

    存储层接收到 Domain 对象之后，将其转化为 PO 对象，然后存储到数据库中。


## 控制参数

控制参数是在 pom 文件中加入的一些参数，用来输入外部的一些参数。这些控制参数都是在 pom 文件中 
properties 节点下的属性定义：

* frameworkPackage
使用 framework 的 package 包名。会影响到 framework 工程的 package。
* frameworkName
框架的名称，会影响到 framework-web 工程里 angularjs 框架使用的 工程名。
* parentGroupId
所有生成的 pom.xml 文件的 parent pom的 groupId
* parentArtifactId
所有生成的 pom.xml 文件的 parent pom的 artifactId
* parentVersion
所有生成的 pom.xml 文件的 parent pom的 version
* parentWebGroupId
所有生成的web插件工程 pom.xml 文件的 parent groupId
* parentWebArtifactId
所有生成的web插件工程 pom.xml 文件的 parent artifactId
* parentWebVersion
所有生成的web插件工程 pom.xml 文件的 parent version
* jhiVersion
使用的 jhipster 版本号，默认是 4.5.1

默认的控制参数定义配置如下：

	<properties>
		<frameworkPackage>com.github.powergen.framework</frameworkPackage>
		<frameworkName>jHipsterFramework</frameworkName>

        <parentGroupId>org.springframework.boot</parentGroupId>
        <parentArtifactId>spring-boot-starter-parent</parentArtifactId>
        <parentVersion>1.5.3.RELEASE</parentVersion>
	</properties>


## 控制标签

控制标签是在 jdl 文件中，每一个 entity 和 property 定义之前的 `/**  */` 块注释内部添加的控制信息。
用于控制生成器的一些行为，增强代码生成的描述能力。

每一个控制标签必须在块注释中占用单独的一行。

* pg-extends: XXX, pg-implements: XXX

    XXX 指定实体的基类或者接口实现，其中的XXX格式定义为 value\[@\[限定,限定,...\]\]
* pg-@XXX(xxx)

    附加实体一些annotation，可以附加在 class 和 field 上。
* pg-id: ... / pg-embeded-id: ...

    复合id，附加在class上，取值是逗号分隔的字段名字列表。
    pg-id 会导致 field 之前加上 @Id 属性，不会生成默认 id 字段。
    pg-embedded-id 是嵌入式Id，默认类型是“类名+Id”，优先级高于 pg-id。
* pg-api-ignore

    附加在 class 上，如果不带参数，就忽略整个 class，如果携带参数，则是逗号分隔的多个值。取值范围是 `get | getall | create | update | delete | search | batch-create | batch-update | batch-delete | last-modified`。例如 `get,delete,search`。
* pg-api-batch: `create | update | delete`

    附加在 class 上，为三个操作提供批量的 REST 接口。目前 delete 尚未实现
* pg-request-mapping

    附加在 class 上，指示 REST 接口的请求 URL 路径。如 `/abc/def`

* pg-last-modified, pg-timestamp, pg-timestamp-key

    附加在 class 上，为 REST 方法附加 getLastModified 方法，配合增量模型同步。要使用本标签，实体对象中必须包含字段携带 pg-timestamp和 pg-timestamp-key。方法的返回值为 pg-timestamp，方法的参数为 pg-timestamp-key。

    pg-last-modified 取值范围是留空或者 serial。 如果是 serial，会忽略 pg-timestamp-key，直接把 pg-timestamp 当做可递增的序列值。

    pg-timestamp, pg-timestamp-key 是附加在 field 上的标注。一个实体内只有一个 pg-timestamp，但是可以有多个 pg-timestamp-key
* pg-find, pg-find-return, pg-find-key, pg-find-orderby

    pg-find 附加在 class 上，提供 逗号分隔的 find group 名称，不在 pg-find 中登记的 group 会被忽略。 find group名称的用途是支持多个 find 标记， 只有相同的 group 中的 find-return和find-key 才能起作用。 pg-find-return、 pg-find-key、 pg-find-orderby 附加在 field 上， 需要附加隶属的 find group 名称。 pg-find-return 也可以附加在class上。

        pg-find: <group1> [, <group2>]*
        pg-find-return: <group-name> [, <distinct|top|first>]
        pg-find-orderby: <group-name> [, <asc|desc>]
        pg-find-key: <group-name> [, <and|or> [, operators]]
        operators = is|equals|less than|....

    具体内容参考 [Spring官方文档]，运算符LessThan变成less than形式。
    例如：
        pg-find: a,b,c
        pg-find-return: a, first10
        pg-find-key: a, and, lessthan
        pg-find-orderby: a, desc

    如果缺少 pg-find-return 则默认返回类对象

* pg-state:

    手工属性，默认全部都要。取值范围为：
    none,create,delete,edit,detail,list, list-edit
* pg-entity: YYY 当前对象是 YYY 对象的简化， 手工属性。

    处理时会将当前对象复制到 YYY 的目录下并改名
* pg-root: XXX, pg-root: -

    表示当前实体的聚合根对象是XXX，如果是“-”则表示本身就是聚合根，手工属性。只有聚合根对象才能生成 restful 的 Resource 对象
* pg-map-to: YYY

    映射view对象到YYY对象的BO，手工属性
* pg-view：XXX,YYY, ...

    帮助在 app/views/下生成特定的视图，里边包含 directive 组合的页面，手工属性
* pg-relationship-XXX: 

    当前对象和 XXX 实体的关联关系，自动生成属性
* pg-relation-dir-XXX: 

    当前对象和 XXx 实体关系的关联方向，自动生成属性

## 示例

1. 创建工程目录结构，一般使用 maven 创建

    mvn -Dfile.encoding=UTF-8 -DinteractiveMode=false -DarchetypeArtifactId=maven-archetype-quickstart  -DarchetypeCatalog=internal archetype:generate -DgroupId=【包名】 -DartifactId=【工程名】 -Dversion=【工程版本】

1. 工程创建完毕之后，目录结构中再添加 model 目录，并在其中放置三个文件，一般如下：

        src
        └── main
            └── model
                ├── 【工程名】.bo.jdl
                ├── 【工程名】.po.jdl
                └── 【工程名】.vo.jdl

    其中，创建bo、po、vo的内容，这里要注意几个要点：
    1. 三个文件中的 entity 名字不可以重复
    1. 实体定义的中心是 BO，一切的 VO 和 PO 都要通过 `pg-entity` 和 BO 进行对应。
        凡是无法对应的实体，VO层面需要手工编码从后台取得正确的数据，PO层面则会变成Repository内部的存取接口。

1. 然后修改 `pom.xml` 文件，增加 parent 内容：

        <parent>
            <groupId>com.github.powergen</groupId>
            <artifactId>powergen-parent</artifactId>
            <version>0.0.1-SNAPSHOT</version>
            <relativePath>../powergen-parent/pom.xml</relativePath>
        </parent>

    如果已经对 powergen-parent 工程执行了 `mvn install` 工作，则可以删掉 `<relativePath>` 部分。

1. 然后创建三个 jdl 文件：

    * VO.jdl 文件内容

            /**
            pg-entity: PortletInfo
            */
            entity PortletInfoPage{
                portletId String required,
                view String required,
                title String required,
                columns Integer ,
                rows Integer
            }

    * BO.jdl 文件内容

            entity PortletInfo{
                portletId String required,
                view String required,
                title String required,
                columns Integer ,
                rows Integer
            }
    
    * PO.jdl 文件内容
    
            /**
            pg-entity: PortletInfo
            */
            entity PortletInfoTable{
                portletId String required,
                view String required,
                title String required,
                columns Integer ,
                rows Integer
            }

1. 执行 `mvn compile` 命令，系统会自动执行代码生成工作，在 `target/sub-projects` 下形成完整的工程。

        sub-projects/
        ├── 【工程名】-application-service
        │   ├── pom.xml
        │   └── src
        ├── 【工程名】-application-service-impl
        │   ├── pom.xml
        │   └── src
        ├── 【工程名】-domain
        │   ├── pom.xml
        │   └── src
        ├── 【工程名】-parent
        │   └── pom.xml
        ├── 【工程名】-repository
        │   ├── pom.xml
        │   └── src
        ├── 【工程名】-repository-impl
        │   ├── pom.xml
        │   └── src
        ├── 【工程名】-web
        │   ├── pom.xml
        │   └── src
        ├── framework
        │   ├── README.md
        │   ├── bower.json
        │   ├── gulp
        │   ├── gulpfile.js
        │   ├── mvnw
        │   ├── mvnw.cmd
        │   ├── node_modules
        │   ├── package.json
        │   ├── pom.xml
        │   └── src
        ├── framework-util
        │   ├── pom.xml
        │   └── src
        └── pom.xml

1. 根据新生成的工程代码，建议使用 目录比较工具（windows建议用 `beyond compare`，mac建议用 `DiffMerge`）
    将其与现有的工作目录进行比较，提取、合并代码到工作目录中。

[Spring官方文档]:https://docs.spring.io/spring-data/jpa/docs/1.7.2.RELEASE/reference/html/#jpa.query-methods.query-creation