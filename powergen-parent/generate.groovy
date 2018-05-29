import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import groovy.xml.QName
import groovy.xml.XmlUtil

import java.util.regex.Pattern
import java.security.MessageDigest

/*
工具处理的目标：
1. 按照 DDD 的设计思想，将系统划分为 web 层， application层， domain层，persistence层。实现
   能够支持分布式部署，分模块开发的架构。
2. web层仅包含所有的html、js和css等在浏览器端展示的资源
3. application层使用rest和web层通讯，包含应用程序特有的逻辑，使用VO作为数据对象。其中：
   rest服务负责处理rest协议相关的工作，application service负责封装application特有逻辑。
4. domain service层处理多个domain之间的相互逻辑，接受 BODTO，并将其转化为 domain 对象。
5. Persistence层接受 Domain 对象作为输入，保留 Domain 转化为 PO的代码，以便紧要关头使用（如网络传输）。

工程划分方面，每一组jdl文件生成的工程，划分为：
1. framework 工程，包括 framework-web 和framework-backend，这部分整个项目只需要一份
2. web 工程，纯前端工程代码。命名为 xxx-web
3. application工程，前端的服务代码。包括 app-service，app-service-impl。
4. domain层，包括 domain
5. persistence层，包括 persistence， persistence-impl

当前架构的分层如下：
    UI -- Application -- Domain -- Repository

职责划分：
    UI：         负责界面内容展示和界面与后台的交互，包含 webapp 部分和 rest 接口部分。默认是 *-web.jar
    Application：负责业务的粘合，将domain之间的交互、流程、规则等逻辑的实现。默认是 *-application-[service|service-impl|mapper].jar
    Domain：     聚合根内部的逻辑实现。默认是 *-domain.jar
    Repository： 默认的持久化存储。默认是 *-repo-[repository|repository-impl|mapper].jar
    Query：      查询和报告的实现。默认是 *-query[-impl].jar

页面的信号传递路径如下：
    html -- VO -- Resource -- VO Service -- BO DTO -- BO Service -- BO ServiceImpl -- ServiceMapper -- Domain --
    -- Repository -- RepositoryImpl -- Mapper -- PO

工程拆分的方法和要点：

    UI：        首先保留VO DTO 和 mapper，但是 Service 不要 impl。放入工程 xxx-web 中的内容包括：
                    dto 需要移动命名空间
                    resource
                    service 需要引用 BO service，可以将原 XXXRepository 修改成 BO Service接口
                    mapper 需要转换到输出 BO DTO
                依赖 bo service 接口

    Application 首先所有实体需要 dto 和 service impl。放入工程中的内容包括：
                    BO DTO 和 BO Service  放到 xxx-application-service 工程中
                    domain 放到 xxx-domain 工程中
                    BO ServiceImpl 和 Mapper 放到了 xxx-application-service-impl 工程中

    Domain      直接参考 application

    Repository  实体需要定义 dto 和 service impl。 放入工程的内容包括：
                    repository 从工程的 service 接口修改而来，需要将参数修改成 BO domain。 且要移动 命名空间。工程名 xxx-repository
                    repository-impl 从工程的 serviceImpl 接口修改而来，需要将参数修改成 BO domain，连带以下的都放入工程 xxx-repository-impl
                    mapper 修改成将 BO domain 转换为 PO
                    domain  修改为 PO， 要为每一个字段定义 @Column 名字， 还需要移动命名空间
                    原 repository 也包含在内


工具处理的逻辑如下：
1. 提取框架代码，并修改代码为注册形式
2. 提取每个层的代码，并拆分出接口定义工程。如果没有相互依赖则建议分多个jdl

系统架构的变化，包括：
1. 基础信息的设置，包括模型文件路径，生成文件的基础目录，文件相对目录，文件名，文件后缀，命名空间
2. 模型内容提取，包括模型定义结构，模型对应的三层文件的位置
3. 几个生成部分的系统文件的合并、修改和迁移

 */


///////////////////////////////////////////////
//
// 基础支撑功能
//
///////////////////////////////////////////////

byte[].metaClass.toHex = {
    byte[] mdbytes = delegate

    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < mdbytes.length; i++) {
        sb.append(Integer.toString((mdbytes[i] & 0xff) + 0x100, 16).substring(1));
    }

    return sb.toString()
}

String.metaClass.upperFirst = { 
    delegate.length() > 0 ? delegate[0].toUpperCase() + delegate.substring(1) : "";
}

// assert "Abc" == "abc".upperFirst()
// assert "Abc" == "Abc".upperFirst()
// assert "_abc" == "_abc".upperFirst()
// assert "" == "".upperFirst()
// assert "0abc" == "0abc".upperFirst()


String.metaClass.lowerFirst = {
    delegate.length() > 0 ? delegate[0].toLowerCase() + delegate.substring(1) : "";
}

// assert "abc" == "abc".lowerFirst()
// assert "abc" == "Abc".lowerFirst()
// assert "_abc" == "_abc".lowerFirst()
// assert "" == "".lowerFirst()
// assert "0abc" == "0abc".lowerFirst()

String.metaClass.camelCase = {
    def arr = delegate.split("\\-")
    StringBuilder sb = new StringBuilder(delegate.length());
    arr.each{ t ->
        if( t.length() > 0 ){
            sb.append(t[0].toUpperCase())
            sb.append(t.substring(1))
        }
    }
    if( sb.length() > 0 )
        sb.setCharAt(0, Character.toLowerCase(sb.charAt(0)))
    return sb.toString();
}

// assert "a1233Bcde" == "a-1-2-3-3-bcde".camelCase()
// assert "aBcdEdf" == "a-bcd-edf".camelCase()
// assert "abc" == "abc".camelCase()
// assert "a" == "a".camelCase()
// assert "1" == "1".camelCase()
// assert "" == "".camelCase()

String.metaClass.dashCase = {
    def str = delegate.replaceAll(/([A-Z]+)/){ part ->
        "-${part[0].toLowerCase()}"
    }

    str = str.replaceAll(/_+\-*/, "-")

    if( str.length() > 0 && str[0] == '-') return str.substring(1)
    return str
}

// assert "abc" == "abc".dashCase()
// assert "aa" == "AA".dashCase()
// assert "aab" == "AAb".dashCase()
// assert "a-ab" == "A_Ab".dashCase()
// assert "c-aab" == "cAAb".dashCase()
// assert "abc" == "Abc".dashCase()
// assert "a-ab-b" == "aAbB".dashCase()
// assert "a-ab-b1c2" == "aAbB1c2".dashCase()
// assert "ab-b" == "AbB".dashCase()
// assert "123a-ab-b" == "123aAbB".dashCase()
// assert "1.2" == "1.2".dashCase()
// assert "1" == "1".dashCase()
// assert "" == "".dashCase()


String.metaClass.checksum = {
    if( delegate.length() == 0 ) return 0

    byte[] buf = delegate.getBytes()

    MessageDigest md = MessageDigest.getInstance("MD5")
    md.update(buf, 0, buf.length)
    md.digest().toHex()
}

// assert "abc".checksum() == "abc".checksum()
// assert "abc1".checksum() != "abc".checksum()


String.metaClass.toFile = {
    return new File(delegate)
}


String.metaClass.isFile = {
    def file = new File(delegate)
    return file.exists() && file.isFile()
}

// assert true == "/etc/passwd".isFile()
// assert false == "/etc/".isFile()
// assert false == "/ab0012343".isFile()

String.metaClass.isDirectory = {
    def file = new File(delegate)
    return file.exists() && file.isDirectory()
}

// assert true == "/".isDirectory()
//assert true == "~".isDirectory()
// assert true == ".".isDirectory()
// assert false == "/etc/passwd".isDirectory()
// assert false == "/ab0012343".isDirectory()

/**
 * 将字符串修改为路径格式，并去掉末尾的路径分隔符
 */
String.metaClass.path = {
    String str = delegate.trim().replaceAll(/[\\\/]+/, "/")
    str.replaceFirst(/([\w\d\-]+)\/$/){ it[0].left(-1)   }
}

// assert "/" == "/".path()
// assert "/a" == "/a".path()
// assert "/a" == "/a/".path()
// assert "a" == "a".path()
// assert "a" == "a/".path()
// assert "/a/1" == "/a/1".path()
// assert "a/1" == "a/1".path()
// assert "a" == "a////".path()
// assert "a/b/c/d" == "a\\b\\c/d\\".path()

/**
 * 将字符串修改为 windows 路径格式，并去掉末尾的路径分隔符
 */
String.metaClass.winpath = {
    String str = delegate.path()
    str.replaceAll(/\//, '\\\\')
}

// assert "\\" == "/".winpath()
// assert "\\a" == "/a".winpath()
// assert "\\a" == "/a/".winpath()
// assert "a" == "a".winpath()
// assert "a" == "a/".winpath()
// assert "\\a\\1" == "/a/1".winpath()
// assert "a\\1" == "a/1".winpath()
// assert "a" == "a////".winpath()
// assert "a\\b\\c\\d" == "a\\b\\c/d\\".winpath()

String.metaClass.canonicalPath = {
    def file = new File(delegate)
    return (file.exists() ? file.canonicalPath : delegate).path()
}

// assert "/" == "/".canonicalPath()
// assert "/bin/bash" == "/bin/bash".canonicalPath()
// assert "/bin" == "/bin/../././././bin/./.".canonicalPath()
// assert "/bin" == "/bin/../bin".canonicalPath()
// assert "/bin/bash" == "/bin/../././././bin/././././bash".canonicalPath()

String.metaClass.lastChar = {
    def size = delegate.length()
    size > 0 ? delegate.charAt(size - 1) : null
}

// assert 'c' == "abc".lastChar()
// assert 'a' == "a".lastChar()
// assert null == "".lastChar()


String.metaClass.firstChar = {
    def size = delegate.length()
    size > 0 ? delegate.charAt(0) : null
}

// assert 'a' == "abc".firstChar()
// assert 'a' == "a".firstChar()
// assert null == "".firstChar()


/**
 * 获取字符串左起的 len 个字符，如果字符数量为负数，则去掉右侧的这几个字符
 */
String.metaClass.left = { len ->
    def size = delegate.length()
    if( size == 0 || len == 0 )
        return ""
    else if( len > 0 )
        return delegate.substring(0, Math.min(size, len))
    else
        return delegate.substring(0, Math.max(size + len, 0))
}

// assert "" == "abc".left(0)
// assert "" == "".left(0)
// assert "a" == "abc".left(1)
// assert "ab" == "abc".left(2)
// assert "a" == "abc".left(-2)
// assert "ab" == "abc".left(-1)
// assert "abc" == "abc".left(10)
// assert "" == "abc".left(-10)

/**
 * 返回寻找位置左侧的字符串
 */
String.metaClass.leftIndexOf = {String ch ->
    def size = delegate.length()
    if( size == 0 || ch == null || ch.length() == 0 )
        return ""

    def pos = delegate.indexOf(ch)
    if( pos >= 0 )
        return delegate.substring(0, pos)
    else
        return ""
}

// assert "" == "abc".leftIndexOf(null)
// assert "" == "abc".leftIndexOf("")
// assert "" == "abc".leftIndexOf("e")

// assert "" == "abc".leftIndexOf("a")
// assert "a" == "abc".leftIndexOf("b")
// assert "ab" == "abc".leftIndexOf("c")
// assert "" == "abc".leftIndexOf("abc")
// assert "a" == "abc".leftIndexOf("bc")

/**
 * 返回寻找最后位置左侧的字符串
 */
String.metaClass.leftLastIndexOf = {String ch ->
    def size = delegate.length()
    if( size == 0 || ch == null || ch.length() == 0 )
        return ""

    def pos = delegate.lastIndexOf(ch)
    if( pos >= 0 )
        return delegate.substring(0, pos)
    else
        return ""
}

// assert "" == "abc".leftLastIndexOf(null)
// assert "" == "abc".leftLastIndexOf("")
// assert "" == "abc".leftLastIndexOf("ee")
// assert "" == "abc".leftLastIndexOf("a")
// assert "a" == "abc".leftLastIndexOf("b")
// assert "ab" == "abc".leftLastIndexOf("c")
// assert "" == "abc".leftLastIndexOf("ab")
// assert "a" == "abc".leftLastIndexOf("bc")


/**
 * 获取右侧的字符数量，如果为负数，则从左侧开始数
 */
String.metaClass.right = { len ->
    def size = delegate.length()
    if( size == 0 || len == 0 )
        return ""
    else if( len > 0 )
        return delegate.substring(Math.max(size - len, 0))
    else
        return delegate.substring(Math.min(-len, size))
}

// assert "" == "abc".right(0)
// assert "" == "".right(0)
// assert "c" == "abc".right(1)
// assert "bc" == "abc".right(2)
// assert "c" == "abc".right(-2)
// assert "bc" == "abc".right(-1)
// assert "abc" == "abc".right(10)
// assert "" == "abc".right(-10)

/**
 * 返回寻找位置右侧的字符串
 */
String.metaClass.rightIndexOf = {String ch ->
    def size = delegate.length()
    if( size == 0 || ch == null || ch.length() == 0 )
        return ""

    def pos = delegate.lastIndexOf(ch)
    if( pos >= 0 )
        return delegate.substring(pos + ch.size())
    else
        return ""
}

// assert "" == "abc".rightIndexOf(null)
// assert "" == "abc".rightIndexOf("")
// assert "" == "abc".rightIndexOf("kkk")

// assert "bc" == "abc".rightIndexOf("a")
// assert "c" == "abc".rightIndexOf("b")
// assert "" == "abc".rightIndexOf("c")
// assert "c" == "abc".rightIndexOf("ab")
// assert "" == "abc".rightIndexOf("bc")
// assert "" == "abc".rightIndexOf("abc")


/**
 * 带 * 的匹配
 * @param str
 * @param matcher
 * @return
 */
String.metaClass.wildMatch = {String matcher ->
    if( matcher == null || matcher.length() == 0 ) return false

    String str = delegate
    String[] parts = matcher.split(/\*+/)
    int pos = 0
    for (int i = 0; i < parts.length; i++) {
        if( parts[i].length() == 0 ) continue

        int s = str.indexOf(parts[i], pos)
        if( s == -1 ){
            return false
        } else {
            pos = s + parts[i].length() + 1
        }
    }

    return true
}

// assert  "abc".wildMatch("abc")
// assert  "abc".wildMatch("a")
// assert  "abc".wildMatch("bc")
// assert  "abc".wildMatch("c")
// assert  "abc".wildMatch("") == false
// assert  "abc".wildMatch("a*c")
// assert  "abc".wildMatch("*bc")
// assert  "abc".wildMatch("ab*")
// assert  "abc".wildMatch("*b*")
// assert  "abc".wildMatch("*")
// assert  "abcd".wildMatch("*b*d")
// assert  "1abcd1".wildMatch("*b*d")
// assert  "1abcd12".wildMatch("*b*d*")
// assert  "a123".wildMatch("*b*d*") == false
// assert  "a123".wildMatch("*b*") == false


/**
 * 移除任何匹配的前缀
 */
String.metaClass.removeAnyPrefix = { String ... prefixs ->
    int maxMatchLen = 0

    for(String str : prefixs){
        def len = str.length()
        if( delegate.startsWith(str) && len > maxMatchLen){
            maxMatchLen = len
        }
    }

    maxMatchLen == 0 ? delegate:  delegate.substring(maxMatchLen)
}

// assert "" == "".removeAnyPrefix("cde", "a", "abc", "abc2")
// assert "" == "abc".removeAnyPrefix("cde", "a", "abc", "abc2")
// assert "1abc" == "1abc".removeAnyPrefix("cde", "a", "abc", "abc2")
// assert "bd" == "abd".removeAnyPrefix("cde", "a", "abc", "abc2")
// assert "111" == "abc111".removeAnyPrefix("cde", "a", "abc", "abc2")

/**
 * 移除任何匹配的前缀
 */
String.metaClass.removeAnyPrefix = { char ... prefixs ->
    def first = delegate.firstChar()

    if( first != null ) {
        for (char ch : prefixs) {
            if (ch == first)
                return delegate.substring(1)
        }
    }

    return delegate
}

// assert "" == "".removeAnyPrefix('a', 'b', 'c')
// assert "d" == "d".removeAnyPrefix('a', 'b', 'c')
// assert "1" == "a1".removeAnyPrefix('a', 'b', 'c')
// assert "b1" == "ab1".removeAnyPrefix('a', 'b', 'c')

/**
 * 按照正则表达式进行转义
 */
String.metaClass.toRegexp = {
    if( delegate.size() == 0 ) return delegate

    StringBuilder sb = new StringBuilder(delegate.size()*2)
    delegate.each { ch ->
        if( /()[]$\.\/-+*?/.indexOf(ch) != -1 ) sb.append('\\')
        sb.append(ch)
    }
    sb.toString()
}

// assert "" == "".toRegexp()
// assert "a" == /a/.toRegexp()
// assert "\\(a\\)" == /(a)/.toRegexp()
// assert "\\(" == /(/.toRegexp()
// assert "\\/" == "/".toRegexp()

String.metaClass.relativePath = { String rootPath ->
    if( delegate.startsWith(rootPath) ) {
        return delegate.substring(rootPath.length()).removeAnyPrefix('/', '\\')
    } else
        return ""
}

// assert "b/c" == "a/b/c".relativePath("a")
// assert "c" == "a/b/c".relativePath("a/b")
// assert "" == "/a/b/c".relativePath("b/c")
// assert "" == "a/b/c".relativePath("/a")

/**
 * 将字符串从路径格式转换为 package
 */
String.metaClass.pathToPackage = {
    delegate.path().replaceAll(/[\\\/]+/, ".")
}

// assert "" == "".pathToPackage()
// assert "a.b" == "a.b".pathToPackage()
// assert "a.b" == "a/b".pathToPackage()
// assert "a.b" == "a\\b".pathToPackage()
// assert "a" == "a".pathToPackage()
// assert "a.b" == "a/b/".pathToPackage()

/**
 * 将字符串从 package 转换为 路径格式
 */
String.metaClass.packageToPath = {
    delegate.replaceAll(/\.+/, "/")
}

// assert "" == "".packageToPath()
// assert "a/b" == "a.b".packageToPath()
// assert "a" == "a".packageToPath()

File.metaClass.checksum = {
    delegate.text.md5
}

///////////////////////////////////////////////
//
// 开始正式功能处理
//
///////////////////////////////////////////////

/**
 * 基础的生成工具，只负责工程的生成相关的工作
 */
class Generator{
    static AntBuilder ant = new AntBuilder()
    static String frameworkArtifactId
    static String frameworkGroupId
    static String parentGroupId, parentArtifactId, parentVersion

    /**
     * 存放命令行的参数
     */
    def options = [:]

    String artifactId, groupId, projectGroupId, version
    String baseDir
    String layer, layerNs

    Generator(String baseDir, String layer, String layerName = layer){
        assert "${baseDir}".isDirectory()
        assert "${baseDir}/pom.xml".isFile()

        this.baseDir = baseDir.canonicalPath()
        this.layer = layer
        this.layerNs = layerName

        extractInfoFromPom(this.baseDir)

        checkProperties()

        assert !"$artifactId".isEmpty()
        assert !"$groupId".isEmpty()

        checkOptions()

        println("""
processing ${layer}[${layerNs}] @ ${this.baseDir}
    groupId: $groupId
    artifactId: $artifactId
        """)
    }

    def checkProperties(){}

    // 从 pom.xml 中获取 groupId 和 artifactId
    private void extractInfoFromPom(String baseDir){
        if( !baseDir.isDirectory() ){
            ant.fail(message: "pom.xml not found")
            return
        }

        def pom = new XmlParser().parse("${baseDir}/pom.xml")
        artifactId = "${pom.artifactId.text()}"
        projectGroupId = pom.groupId.text()
        version = pom.version.text()
        groupId = projectGroupId + (layerNs.length() == 0? "":  ".${layerNs}")
    }

    def checkOptions(){
        System.getenv().each {k,v ->
            if( k.startsWith("build-") || k.startsWith("generate-") ){
                options[k] = v
            }
        }
        options["build-vo"] = "force"
    }

    def getSrcJavaDir(){ "${baseDir}/src/main/java" }
    def getSrcModelDir(){ "${baseDir}/src/main/model" }
    def getJdlName() { "${this.artifactId}.${layer}.jdl" }
    def getProjectTargetDir() { "${baseDir}/target" }
    def getTargetDir() { "${projectTargetDir}/${layer}" }
    def getTargetSrcDir() { "${targetDir}/src" }
    def getTargetSrcJavaDir() { "${targetSrcDir}/main/java" }
    def getPackageDir() { groupId.packageToPath() }

    // 总的生成入口
    public void generate(){
        if( canGenerate() ) {
            preGenerate()
            generateSources()  // 生成源代码
            postGenerate()
        }
    }

    // 判断是否可以生成
    boolean canGenerate(){
        if( ! targetDir.isDirectory() ) return true

        // 如果强制 build
        if( options["build-${layer}"] == "force" ) return true;

        // 如果强制跳过
        if( options["build-${layer}"] == "skip" ) return false;

        if( !checkJdlUpdate("${srcModelDir}/${jdlName}", "${targetDir}/${jdlName}") ) {
            return false
        }
        return true
    }

    // 检查 jdl 是否已经更新
    boolean checkJdlUpdate(String jdlSrcFile, String jdlTarget){
        if( !jdlSrcFile.isFile() ) return false
        if( !jdlTarget.isFile() ) return true

        def ft = jdlTarget.toFile()
        def fs = jdlSrcFile.toFile()

        if (ft.lastModified() < fs.lastModified()) return true
        if (jdlTarget.toFile().size() != jdlSrcFile.toFile().size()) return true

        false
    }

    void preGenerate(){
        checkDirs()  // 确保目录和文件都存在
        copyInitFiles() // 更新初始化文件
    }

    // 检查环境目录是否支持正常生成
    void checkDirs(){
        println("checkDirs")

        def tdir = "${targetDir}"
        if( !tdir.isDirectory() ) tdir.toFile().mkdirs()
        assert tdir.isDirectory()

    }

    // 复制生成必须的初始化文件
    void copyInitFiles(){
        println("copyInitFiles")

        copyJdl()
        copyYoRc()
        linkNodeModules()
    }

    def copyJdl(){
        // 从src/main/model 目录复制jdl文件到 trget 目录
        ant.copy(file:"${srcModelDir}/${jdlName}", tofile:"${targetDir}/${jdlName}", preservelastmodified: true)
        ant.copy(file:"${srcModelDir}/${jdlName}", tofile:"${targetDir}/fix-${jdlName}", preservelastmodified: true)

        assert "${targetDir}/${jdlName}".isFile()
        assert "${targetDir}/fix-${jdlName}".isFile()

        fixJdl("${targetDir}/fix-${jdlName}")
    }

    void fixJdl(def fixJdlFile){}

    def linkNodeModules(){
        // link node_module 目录，可能会因为baseDir中不存在 node_modules 而失败
        if( ! "$targetDir/node_modules".isDirectory() && "$baseDir/node_modules".isDirectory()){
            link("$baseDir/node_modules", "$targetDir/node_modules")

            assert  "$targetDir/node_modules".isDirectory()
        }
    }

    def copyYoRc(){
        // 创建 jhipster的配置文件
        ant.echo(file: "${targetDir}/.yo-rc.json", append:"false", """
        {
            "generator-jhipster": {
                "promptValues": {
                    "packageName": "${this.groupId}",
                    "nativeLanguage": "en"
                },
                "jhipsterVersion": "4.5.2",
                "baseName": "$artifactId",
                "packageName": "${this.groupId}",
                "packageFolder": "$packageDir",
                "serverPort": "8080",
                "authenticationType": "session",
                "hibernateCache": "ehcache",
                "clusteredHttpSession": false,
                "websocket": false,
                "databaseType": "sql",
                "devDatabaseType": "h2Disk",
                "prodDatabaseType": "mysql",
                "searchEngine": false,
                "messageBroker": false,
                "serviceDiscoveryType": false,
                "buildTool": "maven",
                "enableSocialSignIn": false,
                "rememberMeKey": "e832ea76e42281686f61160e8e37d3b5c9707a64",
                "clientFramework": "angular1",
                "useSass": false,
                "clientPackageManager": "yarn",
                "applicationType": "monolith",
                "testFrameworks": [],
                "jhiPrefix": "jhi",
                "enableTranslation": true,
                "nativeLanguage": "en",
                "languages": [
                        "en",
                        "zh-cn"
                ]
            }
        }""")

        assert "${targetDir}/.yo-rc.json".isFile()
    }

    void generateSources(){
        println("generateSources")
        def layerName = layer

        // 如果强制 跳过，则退出
        if( options["generate-${layerName}"] == "skip" ) {
            ant.echo(message: "skip generate ${layerName}")
            return
        }

        if( options["generate-${layerName}"] != "force" ) {
            if( isSourceNewest() )
                return

        } else {
            println("property generate-${layerName} == force, generating ...")
        }

        if( System.getProperty("os.name").toLowerCase().indexOf("windows") >= 0 ) {
            shellscript(shell: "cmd.exe", arg: " /c call ", tmpsuffix: ".bat", dir: "${targetDir}", osfamily: "windows", failonerror: "true", """
                call yo jhipster --force
                if "%errorlevel%" == "0" (
                    if not exist "${targetDir.winpath()}\\src" echo "======src not generated======" && exit 1
                    if exist "fix-${jdlName}" (
                        echo ================generate from ${jdlName}======================
                        call yo jhipster:import-jdl fix-${jdlName} --force
                    )
                ) else (
                    echo "======src not generated======" && exit 1
                )
            """)
        }else {
            shellscript(shell:"bash", dir: "${targetDir}", osfamily:"unix", failonerror:"true", """
                yo jhipster --force
                if [ "\$?" -eq "0"  -a  -f "fix-${jdlName}" ]
                then
                    [ ! -d "${targetDir}/src" ] && echo "=====[${targetDir}/src] not generated======" && exit 1
                    echo "================generate from ${jdlName}======================"
                    yo jhipster:import-jdl fix-${jdlName} --force
                    retcode=\$?
                    echo "================generate finished [\$retcode] ======================"
                    if [ "\$retcode" -ne "0" -o ! -d "node_modules" ] ; then
                        echo "============== rerun yarn --ignore-engines =============="
                        yarn --ignore-engines
                        retcode=\$?
                    fi
                    exit \$retcode
                fi
                """)
        }
    }

    /**
     * 判断源代码是否最近生成的
     * @return
     */
    boolean isSourceNewest(){
        def jdlTarget = "${targetDir}/${jdlName}"
        // package.json 每次都会重新生成，如果 jdl 文件比最后一次生成都要新，则强制生成
        def file = jdlTarget.toFile()
        if (file.exists() && file.lastModified() < "${targetDir}/package.json".toFile().lastModified()) {
            println("File ${jdlName} unmodified, skip generate!")
            return true
        }
        false
    }

    void postGenerate(){
        fixEnv()  // 生成之后的收尾工作
    }

    // 修复生成之后的目录链接情况
    void fixEnv(){
        println("fixEnv")

        if( ! "$targetDir/node_modules".isDirectory() ){
            ant.fail(message: "generate failure! $targetDir/node_modules not found!")
            return
        }

        if( ! "$baseDir/node_modules".isDirectory() ) {
            // move directory
            "$targetDir/node_modules".toFile().renameTo("$baseDir/node_modules".toFile())
            if (!"$baseDir/node_modules".isDirectory()) {
                ant.fail(message: "link node_modules failure!")
            }
        }

        if( ! "$targetDir/node_modules".toFile().exists() ){
            // link directory
            link("$baseDir/node_modules", "$targetDir/node_modules")
        }
    }


    /**
     *
     * @param todir
     * @param filesets closure OR array of map {dir:"", includes:[""], excludes:[""]}
     * @param replaces array of map {match:"", replace:"", flags:""}
     */
    void copyAndReplaceFiles(String todir, def filesets, def replaces = null) {
        def fsets = filesetsToClosure(filesets)

        if( replaces != null ) {
            ant.echo("replace files")
            replaces.each { arr ->
                ant.replaceregexp(match: arr["match"], replace: arr["replace"], flags: arr["flags"]) {
                    fsets()
                }
            }
        }

        ant.echo("copy files to ${todir}")
        ant.copy(todir: todir, overwrite: "true"){
            fsets()
        }
    }

    /**
     * @param filesets  array of map {dir:""; includes:[""]; excludes:[""]}
     */
    def cloneFilesetMapArray(def filesets, def includefilter = null, excludefilter = null){
        if( includefilter == null && excludefilter == null )
            return filesets

        def result = []
        filesets.each{
            def map = [dir: it.dir, includes: [], excludes: []]
            it.includes.each{
                if(includefilter == null || includefilter(it) ){
                    map.includes += it
                }
            }
            it.excludes.each{
                if(excludefilter == null || excludefilter(it) ){
                    map.excludes += it
                }
            }

            if( map.includes.size() > 0 || map.excludes.size() > 0 ) result += map
        }

        result
    }

    /**
     * @param filesets closure OR array of map {dir:""; includes:[""]; excludes:[""]}
     */
    Closure filesetsToClosure(def filesets){
        assert !(filesets instanceof Closure )

        def fsets = {def includefilter = null, def excludefilter = null, def fsCallback = null ->
            filesets.each { item ->
                println("\tdir=${item['dir']}")
                ant.fileset(dir: "${item['dir']}") {
                    item['includes'].each {
                        boolean ok = includefilter == null || ( includefilter != null && includefilter(it) )
                        if( ok ){
                            println("\t\tinclude ${it}")
                            ant.include(name: "${it}")
                        }
                    }
                    item['excludes'].each {
                        boolean ok = excludefilter == null || ( excludefilter != null && excludefilter(it) )
                        if( ok ) {
                            println("\t\texclude ${it}")
                            ant.exclude(name: "${it}")
                        }
                    }
                    if( fsCallback != null && fsCallback instanceof Closure){
                        fsCallback()
                    }
                }
            }
        }

        return fsets
    }

    void link(String srcDir, String linkDir){
        println "link $srcDir ==> $linkDir"

        def parentDir = linkDir.toFile().parent
        def dirname = linkDir.toFile().name

        if( System.getProperty("os.name").toLowerCase().indexOf("windows") >= 0 ) {
            shellscript(shell: "cmd.exe", arg: "/c call ", tmpsuffix: ".bat", dir: "${parentDir}", osfamily: "windows", failonerror: "true", """
                mklink /j ${dirname} "${srcDir.winpath()}"
            """)
        }else {
            shellscript(shell:"bash", dir: "${parentDir}", osfamily:"unix", failonerror:"true", """
                ln -s ${srcDir.path()} ${dirname}
                """)
        }
    }

    void shellscript(def ...args){
        def map = [:]
        def script

        for(def a : args){
            println a
            if( a instanceof Map)
                map += a
            else
                script = a
        }

        if( map.arg == null ) map.arg = " "

        def fname = "${targetDir}/${Math.random()}${map.tmpsuffix?:''}"
        ant.echo(message : script, file:fname, encoding:"utf-8", force:true)
        // TODO fix exec
        ant.echo(message: "exec ${map.shell} @ ${map.dir} arg: ${map.arg} script: ${fname}")
        ant.exec(executable: map["shell"], dir: map["dir"], osfamily: map["osfamily"], failonerror: map["failonerror"], spawn: false){
            arg(line: "${map.arg} ${fname}")
        }

        fname.toFile().deleteOnExit()
    }
}

//new Generator(".", "vo").generate()
//new Generator(".", "bo").generate()
//new Generator(".", "po").generate()
//System.exit(0)

// 所有的生成文件都会被复制到临时目录，然后进行修改，修改完毕之后，放到目标目录
class AnalysisGenerator extends Generator{

    AnalysisGenerator(String baseDir, String layer, String layerName = layer){
        super(baseDir, layer, layerName)
    }

    // 存放实体的信息（实体名：实体参数列表kv），以及关系的信息（实体-实体：关系参数列表kv）
    def entityMap = [:], relationMap = [:], reverseRelationMap = [:]

    def getSubProjectDir() { "${projectTargetDir}/sub-projects" }

    void checkDirs(){
        super.checkDirs()

        def pdir = "${subProjectDir}"
        if( !pdir.isDirectory() ) pdir.toFile().mkdirs()
        assert pdir.isDirectory()
    }


    void analysisJDL(){
        println("analysisJDL")
        println( "jdl file: $targetDir/$jdlName" )
        assert "$targetDir/$jdlName".isFile()

        _load_jdl("$targetDir/$jdlName".toFile().text)

        //assert entityMap.size() > 0
        //assert relationMap.size() > 0
        //assert reverseRelationMap.size() > 0
    }

    public void _load_jdl(String jdl){
        // 存放实体的信息（实体名：实体参数列表kv），以及关系的信息（实体-实体：关系参数列表kv）
        def entityMap = [:], relationMap = [:], reverseRelationMap = [:];

        // 将每个entity关联的信息里所有 pg- 开头的信息全部提取
        // 存放格式是 entity : {pg-KEY: value, ...}
        def parseKV = { String text, map = [:] ->
            (text =~ /(pg\-[\w\d\-]+)\s*:\s*(.+)\s*[\r\n]*/).each{ result ->
                map[result[1]] = result[2];
            }

            return map;
        };

        // 提取entity前面的注释，以及entity相关的所有关系
        def c_comment = "\\/\\*(?:(?!\\*\\/).|[\\n\\r])*\\*\\/";
        def line_comment = "^\\s*\\/\\/.*[\\r\\n]+";
        def maybe_space = "[\\r\\n\\s]*";
        def req_space = "[\\r\\n\\s]+";

        def entity_pattern = "((" + line_comment + ")|(" + c_comment + "))?" + maybe_space + // $1 $2 $3
                "entity" + req_space + "(\\w+)" + maybe_space + "\\{";   // $4 $5

        (jdl =~ entity_pattern).each{ result ->
            // 将实体相关的注释信息放入 entity: [开头注释，[关系，方向, 目标实体, 注释], [], ...]
            entityMap[result[4]] = parseKV(result[3]);
        }

        // 提取relationship中定义的所有关系内容
        def relation_pattern = "((" + line_comment + ")|(" + c_comment + "))" + maybe_space + // $1 $2 $3
                "(\\w+)" + maybe_space + "(\\{.*\\})?" +  // $4 $5
                req_space + "to" + req_space +
                "((" + line_comment + ")|(" + c_comment + "))" + maybe_space + // $6 $7 $8
                "(\\w+)" + maybe_space + "(\\{.*\\})?" + // $9 $10
                "[,\\r\\n\\s]+" + maybe_space;
        def relationship_pattern = "relationship\\s+(\\w+)" + maybe_space + "\\{" + maybe_space + // $1
                "(" + relation_pattern + ")+" + maybe_space +
                "\\}";

        (jdl =~ relationship_pattern).each{ rel_block ->
            def relationType = rel_block[1];

            (rel_block[0] =~ relation_pattern).each{result ->
                // 将实体相关的注释信息放入，双向放入。[关系，方向, 目标实体, 注释]
                relationMap["${result[4]}-${result[9]}"] = parseKV(result[3], ["pg-relation": relationType, "pg-relation-from":result[4], "pg-relation-to":result[9]])
                reverseRelationMap["${result[9]}-${result[4]}"] = parseKV(result[8], ["pg-relation": relationType, "pg-relation-reverse": true, "pg-relation-from":result[4], "pg-relation-to":result[9]])
            }
        }

        println("entityMap ==>" + entityMap);
        println("relationMap ==>" + relationMap);
        println("reverseRelationMap ==>" + reverseRelationMap);

        this.entityMap = entityMap
        this.relationMap = relationMap
        this.reverseRelationMap = reverseRelationMap
    }

    void processEntities(){
        println("processEntities")

        // 遍历实体中的每一个元素
        this.entityMap.each{ ent ->
            String entityName = ent.key // 保存实体名称
            // 获取实体名字开头的所有 导出和导入的关联
            def relations = this.relationMap.findAll {it.key.startsWith("${entityName}-")}.collect {it.key}
            def revrel = this.reverseRelationMap.findAll {it.key.startsWith("${entityName}-")}.collect {it.key}
            def files = listEntityFiles(entityName)

            // 处理单个实体
            ant.echo(message: "------- processing entity ${entityName}")
            processEachEntity(entityName, ent.value, relations, revrel, files)
            ant.echo(message: "------- end process entity ${entityName}")
        }
    }

    // 列举和这个实体相关的所有文件
    def listEntityFiles(String entityName){
        return []
    }

    void processEachEntity(String entityName, def entityOptions, def relationNames, def reverseRelationNames, def files){
        println("    ${entityOptions}")
        println("    ${relationNames}")
        println("    ${reverseRelationNames}")
        println("    ${files}")
    }

    void postProcess(){
        // 检查 subproject 目录下是否有 module pom，没有则创建一个
        if( "$subProjectDir/pom.xml".toFile().exists() == false ){
            ant.echo(file: "$subProjectDir/pom.xml", append:"false", message: """
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>${projectGroupId}</groupId>
    <artifactId>${this.artifactId}-build</artifactId>
    <packaging>pom</packaging>
    <version>$version</version>

    <modules>

    </modules>
</project>

""")
        }
    }

    void postGenerate(){
        super.postGenerate()

        analysisJDL() // 分析jdl

        cloneSourceToTemp() // 将源代码保存一个副本，便于修改
        processEntities() // 对源代码进行加工

        postProcess() // 加工完成之后的后续处理
    }

    String getTempDir(){ "${targetDir}/cloneTemp" }
    String getTempSrcWebDir(){ "${tempDir}/main/webapp" }
    String getTempSrcJavaDir(){ "${tempDir}/main/java" }

    void cloneSourceToTemp(){
        ant.echo(message: "copy project files to temp")
        ant.delete(dir: tempDir)
        //  需要原封不动复制的文件
        ant.copy(todir:"${tempDir}", overwrite:"true") {
            fileset(dir: "${targetSrcDir}") {
                include(name: "**/*.*")
                exclude(name: "**/webapp/bower_components/**/*")
            }
        }
    }

    /**
     * 修改 class 名称，并移动 class 文件
     * @param toDir
     * @param classMapArray 映射class 的数组，例如 [  [from: "", to: ""], [from: "", to: ""], ...  ]
     * @param files
     * @param afterReplaceCallback  在内容替换完成之后的回调函数
     */
    void replaceClass(String toDir, def classMapArray, def files, def afterReplaceCallback = null) {
        classMapArray.each { classMap ->
            String fromClass = classMap.from
            String toClass = classMap.to

            System.out.println("replace Class from $fromClass to $toClass")
            if (fromClass == toClass) return

            // 将 class 改名，同时修改 class 的引用 和 文件名
            String fromClassName = fromClass.rightIndexOf(".")
            String fromClassPkg = fromClass.leftLastIndexOf(".")

            String toClassName = toClass.rightIndexOf(".")
            String toClassPkg = toClass.leftLastIndexOf(".")

            // 先替换全名的引用
            replaceText(/\b${fromClass.toRegexp()}\b/, /${toClass.toRegexp()}/, "gm", files)

            // 如果 package 不相等
            if (fromClassPkg != toClassPkg) {
                // 再替换 package xxxx; 的声明
                System.out.println("replace package from $fromClassPkg to $toClassPkg")
                replaceText(/^\s*package ${fromClassPkg.toRegexp()}\s*;/, /package ${toClassPkg.toRegexp()};/, "gm",
                        cloneFilesetMapArray(files) {
                            it.endsWith("/${fromClassName}.java")
                        })
            }
            if (fromClassName != toClassName) {
                // 替换类名引用
                replaceText(/\b${fromClassName}\b/, /${toClassName}/, "gm", files)
                // 替换变量名
                replaceText(/\b${fromClassName.lowerFirst()}\b/, /${toClassName.lowerFirst()}/, "gm", files)
            }
        }

        if( afterReplaceCallback != null )  afterReplaceCallback()

        classMapArray.each { classMap ->
            String fromClass = classMap.from
            String toClass = classMap.to

            if (fromClass == toClass) return

            // 将 class 改名，同时修改 class 的引用 和 文件名
            String fromClassName = fromClass.rightIndexOf(".")
            String fromClassPkg = fromClass.leftLastIndexOf(".")

            String toClassName = toClass.rightIndexOf(".")
            String toClassPkg = toClass.leftLastIndexOf(".")

            def fsets = filesetsToClosure(files)

            // 移动文件并改名
            ant.move(todir: "$toDir/${toClassPkg.packageToPath()}", includeemptydirs: "true", overwrite: "true") {
                fsets()
                globmapper(handledirsep: "yes",
                        from: "*/${fromClassName}.java",
                        to: "${toClassName}.java")
            }
        }
    }

    void replaceText(String match, String replace, String flags, def files){
        if( files.size() == 0 ) return

        def fsets = filesetsToClosure(files)
        // 替换文件中的内容
        ant.replaceregexp(match: match, replace: replace, flags: flags){
            fsets()
        }
    }

    /**
     * 复制并修改 pom 文件内容
     * @param fromPom   来源 pom 的路径
     * @param toPom     目标 pom 的路径
     * @param nodes     需要修改的节点数组，内容是 [ xpath: value ]
     * @param dependencies  需要添加的依赖，内容是 [ group:artifact:version, ... ]
     * @param callback  回调函数，用于对 pom 的xml 进行自定义的操作
     */
    def copyPomXml(String fromPom, String toPom, def nodes, def dependencies,  @ClosureParams(value=SimpleType.class, options="groovy.util.Node") Closure<Boolean> callback = null){
        ant.copy(file: fromPom, tofile: toPom, overwrite: "true")
        assert toPom.toFile().exists()

        def pom = new XmlParser().parse(toPom)

        if( callback != null && callback instanceof Closure) callback(pom)

        if( nodes != null ) nodes.each{ key, val ->
            def curnode = pom
            key.split(/[\/]/).each{
                if( it.length() > 0 ) {
                    curnode = createOrGetChild(curnode, it)
                    // groovy.util.Node 530 行， GStringImpl 和 String 比较有问题
                    // curnode = curnode."$it".size() > 0 ? curnode."$it"[0] : curnode.appendNode("$it")
                }
            }

            curnode.value = val
        }

        if( dependencies != null ) dependencies.each{
            def arr = it.split(/\s*:\s*/)
            def grp = arr.size() > 0 ? arr[0]: null
            def art = arr.size() > 1 ? arr[1]: null
            def ver = arr.size() > 2 ? arr[2]: null
            def scope = arr.size() > 3 ? arr[3]: null

            if( !(grp == null && art == null && ver == null) ){
                def node = pom.dependencies[0]
                if( node == null ) node = pom.appendNode("dependencies")
                def dep = node.appendNode("dependency")
                if( grp != null ) dep.appendNode("groupId", grp)
                if( art != null ) dep.appendNode("artifactId", art)
                if( ver != null ) dep.appendNode("version", ver)
                if( scope != null ) dep.appendNode("scope", scope)
            }
        }

        if( dependencies != null && nodes != null && (nodes.size() > 0 || dependencies.size() > 0) ) {
            XmlUtil.serialize(pom, toPom.toFile().newWriter("utf-8"))
        }
    }

    def createOrGetChild(def curnode, String it){
        if( curnode."$it".size() > 0 ){
            return curnode."$it"[0]
        }else{
            def retnode = curnode.children().find {childNode ->
                Object childNodeName = childNode.name();
                if (childNodeName instanceof QName) {
                    QName qn = (QName) childNodeName;
                    if (qn.matches(it)) {
                        return true;
                    }
                } else if (it.equals(childNodeName.toString())) {
                    return true;
                }
                return false;
            }
            return retnode ?: curnode.appendNode("$it")
        }
    }

    void addPomModule(String pom, String moduleName){
        ant.replaceregexp(match: /\s*\<module\>${moduleName.toRegexp()}\<\/module\>\s*/,
                replace: "", flags: "gm"){
            fileset(file: pom)
        }
        ant.replaceregexp(match: /(\s*\<\/modules\>)/,
                replace: "\n\\<module\\>${moduleName.toRegexp()}\\<\\/module\\>\n\\1", flags: "gm"){
            fileset(file: pom)
        }
    }

//    void aaa(def v){
//        replaceClass("${tempSrcJavaDir}", "com.fogsun.example.view.domain.MemberView", "com.fogsun.example.view.domain123.MemberAbcView", [
//                [dir: "${tempSrcJavaDir}", includes: ["**/*.java"]]
//        ])
//    }
//
//    void dbbb(){
//        copyPomXml("$targetDir/pom.xml", "$targetDir/pom123.xml",
//                ["artifactId": "abc", "parent/version":"1.2.3"],
//                ["abc:cde:1", "mmm:ddd:new1"]
//        )
//    }
//
//    void ddd(){
//        "$subProjectDir/pom.xml".toFile().delete()
//        postProcess()
//        addPomModule("$subProjectDir/pom.xml", "abc")
//        addPomModule("$subProjectDir/pom.xml", "abc")
//        addPomModule("$subProjectDir/pom.xml", "abc-123")
//        addPomModule("$subProjectDir/pom.xml", "abc-123")
//        addPomModule("$subProjectDir/pom.xml", "abc123")
//        println("$subProjectDir/pom.xml".toFile().text)
//    }
}

//def ag = new AnalysisGenerator("/Users/jiangjianbo/work/tech/powergen/powergen-test", "vo", "view")
//ag.generate()
//ag.aaa()
//ag.dbbb()
//ag.ddd()
//System.exit(0)

/*
<!--  目前 jdl 中每一个实体可以指定如下的标记，需要附加一些标准的处理过程插件
pg-extends: XXX, pg-implements: XXX 指定实体的基类或者接口实现
pg-@XXX(xxx) 附加实体一些annotation
pg-state: none,create,delete,edit,detail,list, list-edit，手工属性，默认全部都要
pg-entity: YYY 当前对象是 YYY 对象的简化， 手工属性。处理时会将当前对象复制到 YYY 的目录下并改名
pg-map-to: YYY   映射view对象到YYY对象的BO，手工属性
pg-view：XXX,YYY, ... 帮助在 app/views/下生成特定的视图，里边包含 directive 组合的页面，手工属性
pg-relationship-XXX: 当前对象和 XXX 实体的关联关系，自动生成属性
pg-relation-dir-XXX: 当前对象和 XXx 实体关系的关联方向，自动生成属性
可用变量： entity.name, entity.dash-name
-->
*/
class AnalysisWithActionGenerator extends AnalysisGenerator{

    AnalysisWithActionGenerator(String baseDir, String layer, String layerName = layer) {
        super(baseDir, layer, layerName)
    }

    def splitJavaFile(File file){
        def text = file.getText("utf-8")
        def result = ["@begin": null, "@end": null, "@order": null]

        // 提取entity前面的注释，以及entity相关的所有关系
        def c_comment = /\/\*(?:(?!\*\/).|[\n\r])*\*\//;
        def line_comment = /\s*\/\/.*[\r\n]+/;
        def maybe_space = /[\r\n\s]*/;
        def req_space = /[\r\n\s]+/;

        //def splitter = "(?:($c_comment)|($line_comment)|($req_space))+"

        //def class_def = /^\s*public\s+class\s+(\w+)\s*\{/
        def method_def = /public\s+\w+[\<\>\s\w\d]*?\s+(\w+)\s*\(/  // $1

        int start = 0
        String methodName = null
        def methodOrder = []

        def m = text =~ method_def
        while( m.find() ){
            int pos0 = m.start()
            int pos = text.lastIndexOf("/**", pos0)

            if( start == 0 ){
                result["@begin"] = text[start .. pos-1]
            }else{
                if( methodName != null ) {
                    result[methodName] = text[start..pos - 1]
                    methodOrder += methodName
                }
                methodName = m.group(1)
            }

            start = pos
        }

        // 寻找最后一个花括号
        int last = text.lastIndexOf('}')
        result[methodName] = text[start .. last-1]
        methodOrder += methodName

        result["@end"] = text[last .. text.size()-1]
        result["@order"] = methodOrder

        result
    }

    /**
     * 对于不带 “@” 符号的，默认要和 entityName 同名
     * @return
     */
    boolean matchActionOption(String entityName, String fileName, String optionValue){
        String fname = fileName.leftLastIndexOf(".")

        // 没有 @ 则表示严格和实体名匹配
        if( -1 == optionValue.indexOf('@') ){
            return entityName == fname
        }else {
            String[] parts = optionValue.rightIndexOf("@").trim().split(",")
            if( parts.length == 0 ){    // 如果只有单个 @，则匹配所有
                return true
            } else {
                // 否则一个一个拿出来匹配，支持尾部匹配 和 通配符号匹配
                for (int i = 0; i < parts.length; i++) {
                    if( fname.endsWith(parts[i]) || fname.wildMatch(parts[i])){
                        return true
                    }
                }
            }
        }

        return false
    }

    String extractActionValue(String optionValue){
        int pos = optionValue.indexOf('@')
        if( pos != -1 ){
            return optionValue.substring(0, pos)
        } else {
            return optionValue
        }

    }

    def actions = [
            "pg-extends": {String entityName, def relationNames, def reverseRelationNames, File file, String optionKey, def optionValue ->
                assert "$optionValue".isEmpty() == false: "pg-extends require agrument"
                assert "$optionValue".matches(/\s*\w+\s*(@\w*(\s*,\s*\w+)*)?/): "pg-extends require only one class name"

                String filename = file.name
                if( filename.endsWith(".java") && matchActionOption(entityName, filename, optionValue) ){
                    String oval = extractActionValue(optionValue)
                    // replace and save extends
                    ant.replaceregexp(
                            //         $1                   $2             $3                        $4     $5
                            match: /^(\s*)public\s+class\s+(\w+)([\s\r\n]+extends[\s\r\n]+(\w+))?([\s\r\n]+implements\s+\w+\s*(,\s*\w+\s*)*)?\s*\{/,
                            replace: "\\1public class \\2 extends ${oval} \\5 { /* \\3 */  ", flags: "gm"){
                        fileset(file: "${file.absoluteFile}")
                    }

                }
            },
            "pg-implements": {String entityName, def relationNames, def reverseRelationNames, File file, String optionKey,  def optionValue ->
                assert "$optionValue".isEmpty() == false: "pg-implements require agrument"
                assert "$optionValue".matches(/\s*\w+\s*(,\s*\w+)*(\s*@\w*(\s*,\s*\w+)*)?/): "pg-implements require one or more class name"

                String filename = file.name
                if( filename.endsWith(".java") ){
                    ant.replaceregexp(
                            //         $1          $2                          $3             $4                        $5     $6                     $7      $8
                            match: /^(\s*)public\s+((?:class)|(?:interface))\s+(\w+)([\s\r\n]+extends[\s\r\n]+(\w+))?([\s\r\n]+implements\s+(\w+)\s*(,\s*\w+\s*)*)?\s*\{/,
                            replace: "\\1public \\2 \\3 \\4 implements $optionValue, \\7 \\8 {", flags: "gm"){
                        fileset(file: "${file.absoluteFile}")
                    }

                }
            },
            "pg-annotation": {String entityName, def relationNames, def reverseRelationNames, File file, String optionKey, def optionValue ->

            },
            "pg-@": {String entityName, def relationNames, def reverseRelationNames, File file, String optionKey, def optionValue ->

            },
            "pg-state": {String entityName, def relationNames, def reverseRelationNames, File file, String optionKey, def optionValue ->
                String filename = file.name
                if( filename == "${entityName}Resource.java" ){
                    println("\tpg-state of $filename ")
                    processState(entityName, relationNames, reverseRelationNames, file, optionValue,
                            {state, entName ->
                                def stateMap = [
                                        "create": ["create@"],
                                        "delete": ["delete@"],
                                        "edit": ["update@", "create@"],
                                        "detail": ["get@"],
                                        "list": ["getAll@s"],
                                        "list-edit": ["update@"]
                                ]
                                //none, create, delete,edit,detail,list, list-edit
                                def pattern = stateMap[state]
                                assert pattern != null && pattern.size() > 0, "unknown pg-state value [$state]"

                                pattern.collect{it.replaceAll("@", entName)}
                            },
                            { selects ->
                                def fileContent = splitJavaFile(file)
                                StringBuilder sb = new StringBuilder(10*1024)
                                sb.append(fileContent["@begin"])
                                if( selects.size() > 0 ) {
                                    fileContent["@order"].each {
                                        if( selects.contains(it) ){
                                            sb.append(fileContent[it])
                                        }
                                    }
                                }
                                sb.append(fileContent["@end"])
                                // 更新文件内容
                                file.setText(sb.toString())
                            }
                    )
                } else if( filename.endsWith(".html") ){
                    println("\tpg-state of $filename ")
                    processState(entityName, relationNames, reverseRelationNames, file, optionValue,
                            {state, entName ->
                                def stateMap = [
                                        "create": ["@-dialog.html"],
                                        "delete": ["@-delete-dialog.html"],
                                        "edit": ["@-dialog.html"],
                                        "detail": ["@-detail.html"],
                                        "list": ["@s.html"],
                                        "list-edit": ["@s.html"]
                                ]
                                //none, create, delete,edit,detail,list, list-edit
                                def pattern = stateMap[state]
                                assert pattern != null && pattern.size() > 0, "unknown pg-state value [$state]"

                                pattern.collect{it.replaceAll("@", "$entName".dashCase())}
                            },
                            { selects ->
                                if( selects.size() > 0 && !selects.contains(file.name) ){
                                    file.delete()
                                }
                            }
                    )
                }
            },
            "pg-entity": {String entityName, def relationNames, def reverseRelationNames, File file, String optionKey, def optionValue ->

            },
            "pg-map-to": {String entityName, def relationNames, def reverseRelationNames, File file, String optionKey, def optionValue ->

            },
            "pg-view": {String entityName, def relationNames, def reverseRelationNames, File file, String optionKey, def optionValue ->

            },
            "pg-relationship": {String entityName, def relationNames, def reverseRelationNames, File file, String optionKey, def optionValue ->

            },
            "pg-relation-dir": {String entityName, def relationNames, def reverseRelationNames, File file, String optionKey, def optionValue ->

            },
    ]

    /**
     * 调用处理函数
     * @param entityName 当前处理的实体名
     * @param options 处理标记名称
     * @param relationNames 实体的关系
     * @param reverseRelationNames 实体的反向关系列表
     * @param files 实体相关的文件对象，File[]
     */
    void callJavaAction(String entityName, def options, def relationNames, def reverseRelationNames, def files){
        println("=== processing entity $entityName ==")
        options.each{key, val ->
            def func = actions[key]
            if( func == null ) {
                def len = 0, selK = null
                // 如果没找到 action， 则将action的内容列出，取匹配最长的进行处理
                actions.keySet().each {
                    if( key.startsWith(it) && it.length() > len ){
                        len = it.length()
                        selK = it
                    }
                }

                if( selK != null ) func = actions[selK]
            }
            if( func != null ){
                files.each{
                    println("\t\t\t $entityName $key $val $it ")
                    func(entityName, relationNames, reverseRelationNames, it, key, val)
                }
            }
        }

    }

    void processState(String entityName, def relationNames, def reverseRelationNames, File file,
                      def optionValue, def selector, def handler){
        // 分解 option value
        def states = "$optionValue".split(/\s*,\s*/)

        // 提取合法的方法
        if( states.size() > 0 && !states.contains("all") ){
            def selects = []

            if( states.contains("none") ){
            } else {
                states.each {
                    // 挑出需要选择的内容
                    selects += selector(it, entityName)
                }

                handler(selects)
            }
        }
    }
}

//println(
//        new AnalysisWithActionGenerator("/Users/jiangjianbo/work/tech/powergen/powergen-test", "ww", "ww").splitJavaFile(
//        "/Users/jiangjianbo/work/tech/powergen/powergen-test/src/main/java/com/fogsun/example/web/rest/UserResource.java")
//)
//System.exit(0)


// 负责从代码中提取基础的框架代码，并将其插件化
class FrameworkGenerator extends AnalysisWithActionGenerator{

    String savedGroupId, savedArtifactId

    public FrameworkGenerator(String baseDir){
        super(baseDir, "framework")

        savedGroupId = this.groupId
        savedArtifactId = this.artifactId

        this.groupId = frameworkGroupId
        this.artifactId = frameworkArtifactId
    }

    boolean checkJdlUpdate(String jdlSrcFile, String jdlTarget){ true }

    def copyJdl(){
        // 从src/main/model 目录复制jdl文件到 trget 目录
        ant.echo(file:"${targetDir}/${jdlName}", message: "entity TempABC {}")
        ant.echo(file:"${targetDir}/fix-${jdlName}", message: """
entity TempABC {}
dto * with mapstruct
service * with serviceImpl
        """, append: false)

        assert "${targetDir}/${jdlName}".isFile()
        assert "${targetDir}/fix-${jdlName}".isFile()
    }

    // 分析jdl
    void analysisJDL() {}

    // 对源代码进行加工
    void processEntities() {}

    // 加工完成之后的后续处理
    void postProcess(){
        super.postProcess()

        // 这里复制和修理所有的文件，需要拆分出多个工程，包括 framework， framework-util
        ant.echo("extracting framework sources")

        def subdir1 = "${subProjectDir}/framework-util/src/main/java/"
        def utilMaps = [
                [dir: "$tempSrcJavaDir", includes: [
                        "${this.groupId}.service.mapper.EntityMapper".packageToPath() + ".java",
                        "${this.groupId}.web.rest.util.HeaderUtil".packageToPath() + ".java",
                        "${this.groupId}.web.rest.util.PaginationUtil".packageToPath() + ".java"
                ]]
        ]
        copyAndReplaceFiles(subdir1, utilMaps)
        ant.delete(){
            filesetsToClosure(utilMaps)()
        }
        assert ("$tempSrcJavaDir/${this.groupId}.service.mapper.EntityMapper".packageToPath() + ".java").isFile() == false
        assert ("$tempSrcJavaDir/${this.groupId}.web.rest.util.HeaderUtil".packageToPath() + ".java").isFile() == false
        assert ("$tempSrcJavaDir/${this.groupId}.web.rest.util.PaginationUtil".packageToPath() + ".java").isFile() == false

        copyAndReplaceFiles("${subProjectDir}/framework/src",
                [
                        [dir:"${tempDir}",
                         includes:["**/*"],
                         excludes:["**/TempABC*.*", "**/temp-abc*.*", "**/temp-abc/**/*", "temp-abc", "node_modules/**/*"]]
                ],
                [
                        [match:/^\s*.*TempABC.+;\s*$/, replace:"", flags:"gm"]
                ]
        )

        // 修改 config/CacheConfiguration.java 中的注册内容

        // 删除文件中的 temp-abc 相关的内容
        ant.replaceregexp(match: /^\s*<script\s+src="app\/entities\/temp\-abc\/temp\-abc.+"\s*>\s*<\/script>\s*$/, replace: "", flags: "gm"){
            fileset(file: "${subProjectDir}/framework/src/main/webapp/index.html")
        }
        ant.replaceregexp(match: /^\s*<include file="classpath:config\/liquibase\/changelog\/.+_added_entity_TempABC.xml" relativeToChangelogFile="false"\/>\s*$/, replace: "", flags: "gm"){
            fileset(file: "${subProjectDir}/framework/src/main/resources/config/liquibase/master.xml")
        }
        ant.delete(){
            fileset(file: "${subProjectDir}/framework/src/main/resources/config/liquibase/changelog/*_added_entity_TempABC.xml")
            fileset(dir: "${subProjectDir}/framework/src/main/webapp/app/entities/temp-abc")
            fileset(dir: "${subProjectDir}/framework/src/main/webapp/i18n"){
                include(name: "**/*/tempABC.json")
            }
        }
        ant.replaceregexp(match: /^\s*<a\s+ui\-sref="temp\-abc"[\s\S]+?data\-translate="global\.menu\.entities\.tempAbc">.+<\/span>[\s\S]+?<\/a>\s*$/,
                replace: "", flags: "gm"){
            fileset(file: "${subProjectDir}/framework/src/main/webapp/app/layouts/navbar/navbar.html")
        }
        ant.replaceregexp(match: /^\s*"tempAbc"\s*:\s*".*"\s*,\s*$/, replace: "", flags: "gm"){
            fileset(dir: "${subProjectDir}/framework/src/main/webapp/i18n"){
                include(name: "**/*/global.json")
            }
        }

        // 复制 bower_components
        copyAndReplaceFiles("${subProjectDir}/framework/src",
                [
                        [dir:"${targetSrcDir}", includes:["main/webapp/bower_components/**/*"], excludes:[ ]]
                ], [ ]
        )

        copyAndReplaceFiles("${subProjectDir}/framework",
                [
                        [dir:"${targetDir}", includes:["*", "*.*", ".mvn/**/*.*", "gulp/*.*"], excludes:[ "*.jdl", "pom.xml", "0.*"]]
                ], [ ]
        )

        if( ! "${subProjectDir}/framework/node_modules".isDirectory() ){
            link("$baseDir/node_modules", "${subProjectDir}/framework/node_modules")
        }

        // 创建 parent pom 文件
        copyPomXml("$targetDir/pom.xml", "${subProjectDir}/${savedArtifactId}-parent/pom.xml",
                [
                        "parent/groupId" : "$parentGroupId",
                        "parent/artifactId" : "$parentArtifactId",
                        "parent/version" : "$parentVersion",

                        "groupId": "$projectGroupId",
                        "artifactId": "${savedArtifactId}-parent",
                        "version": "$version",
                        "packaging": "pom",

                        "name": "$savedArtifactId parent POM project",

                        "properties/maven.version" : "3.0.0",
                        "properties/java.version" : "1.8",
                        "properties/maven.compiler.source" : "\$"+"{java.version}",
                        "properties/maven.compiler.target" : "\$"+"{java.version}",

                        "properties/project.build.sourceEncoding" : "UTF-8",

                        "properties/maven.build.timestamp.format" : "yyyyMMddHHmmss",

                        "properties/mapstruct.version" : "1.1.0.Final",
                        "properties/dropwizard-metrics.version" : "3.2.2",
                        "properties/jhipster.server.version" : "1.1.4",

                        "properties/swagger-annotations.version": "1.5.13",
                        "properties/webjars-locator.version": "0.33",
                        "properties/metainf-services.version": "1.7"
                ], [
                        "org.slf4j:slf4j-api"
                ]
        ){ pom ->
            pom.remove(pom.properties)
            pom.remove(pom.dependencies)
            pom.remove(pom.build)
            pom.remove(pom.profiles)
        }

        // 创建 pom 文件
        copyPomXml("$targetDir/pom.xml", "${subProjectDir}/framework-util/pom.xml",
                [
                        "groupId": "$frameworkGroupId",
                        "artifactId": "framework-util",
                        "packaging": "jar",
                        "name": "$artifactId framework util project"
                ], [
                        "org.springframework.boot:spring-boot-starter-data-jpa",
                        "org.springframework.boot:spring-boot-starter-web",
                        "org.slf4j:slf4j-api"
                ]
        ){ pom ->
            pom.remove(pom.properties)
            pom.remove(pom.dependencies)
            pom.remove(pom.build)
            pom.remove(pom.profiles)
        }

        // 创建 pom 文件
        copyPomXml("$targetDir/pom.xml", "${subProjectDir}/framework/pom.xml",
                [
                        "groupId": "$frameworkGroupId",
                        "artifactId": "framework",
                        "name": "$artifactId framework project"
                ], [
                        "${frameworkGroupId}:framework-util:$version" // 依赖 framework-util
                ]
        )

        addPomModule("$subProjectDir/pom.xml", "framework")
        addPomModule("$subProjectDir/pom.xml", "framework-util")

    }
}

//new FrameworkGenerator(".").generate()
//System.exit(0)

class VoGenerator extends AnalysisWithActionGenerator{
    String getEntitiesDir(){ "$tempSrcWebDir/app/entities" }

    public VoGenerator(String baseDir){
        super(baseDir, "vo", "web")
    }

    void fixJdl(def fixJdlFile){
        // 删除 vo.jdl 中，所有的 dto， service impl 设置，重新添加一个标准的
        ant.replaceregexp(match: /^\s*dto\s+.+\s+with\s+mapstruct.*$/, replace: "", flags: "m"){
            fileset(file: "$fixJdlFile")
        }

        ant.replaceregexp(match: /^\s*service\s+.+\s+with\s+serviceImpl.*$/, replace: "", flags: "m"){
            fileset(file: "$fixJdlFile")
        }

        // 如果去掉 service 行，则所有的逻辑代码在 Resource 中，就不存在 Service 文件了
        ant.echo(file: "$fixJdlFile", message: """
dto * with mapstruct
// service * with serviceImpl
        """, append: true)

    }

    def listEntityFiles(String entityName){
        // 查找 webapp 下的文件
        def webfiles = "${entitiesDir}/${entityName.dashCase()}".toFile().listFiles({File f -> !f.isDirectory() } as FileFilter)

        String entJavaDir = "${tempSrcJavaDir}/${groupId.packageToPath()}"

        // 注意，如果没有 serviceImpl，则要处理 service
        def javafiles = [
                "domain": ".java",
                "service": "Service.java",
                "service/dto": "DTO.java",
                "service/impl": "ServiceImpl.java",
                "service/mapper": "Mapper.java",
                "repository": "Repository.java",
                "web/rest": "Resource.java"
        ].collect { ent ->
            def f = "$entJavaDir/${ent.key}/${entityName}${ent.value}".toFile()
            println( f )
            f.isFile() ? f : null
        }.findAll { it != null }

        return [webfiles, javafiles]
    }

    void processEachEntity(String entityName, def entityOptions, def relationNames, def reverseRelationNames, def files){
        println("    ${entityOptions}")
        println("    ${relationNames}")
        println("    ${reverseRelationNames}")
        println("    ${files}")

        // 修改文件内容
        extractJavaFiles(entityName, entityOptions, relationNames, reverseRelationNames, files[1])
        // 提取文件中的组件
        extractComponents(entityName, entityOptions,  relationNames, reverseRelationNames, files[0])
    }

    void extractJavaFiles(entityName, entityOptions, relationNames, reverseRelationNames, absfiles){
        // 先根据实体的 annotation 处理文件内容
        callJavaAction(entityName, entityOptions, relationNames, reverseRelationNames, absfiles)

        def parentDir1 = "${tempSrcJavaDir}/${groupId.packageToPath()}"
        def relFiles = absfiles.findAll{ it.absolutePath.startsWith(parentDir1) && it.exists() }.collect{File f ->
            "${f.absolutePath.relativePath(parentDir1)}"
        }
        def files = [
                [dir: "$parentDir1", includes: relFiles]
        ]

        String boName = entityOptions["pg-entity"] ?: "${entityName}BO"
        assert "$tempSrcJavaDir/${groupId.packageToPath()}/web/rest/${entityName}Resource.java".isFile()
        assert "$tempSrcJavaDir/${groupId.packageToPath()}/service/dto/${entityName}DTO.java".isFile()
        assert "$tempSrcJavaDir/${groupId.packageToPath()}/service/mapper/${entityName}Mapper.java".isFile()
        assert "$tempSrcJavaDir/${groupId.packageToPath()}/domain/${entityName}.java".isFile()
        assert "$tempSrcJavaDir/${groupId.packageToPath()}/repository/${entityName}Repository.java".isFile()
        
        replaceClass("$tempSrcJavaDir", [
                [from: "${this.groupId}.service.mapper.EntityMapper", to: "${frameworkGroupId}.service.mapper.EntityMapper"],
                [from: "${this.groupId}.web.rest.util.HeaderUtil", to: "${frameworkGroupId}.web.rest.util.HeaderUtil"],
                [from: "${this.groupId}.web.rest.util.PaginationUtil", to: "${frameworkGroupId}.web.rest.util.PaginationUtil"],
                // 移动 DTO 的命名空间
                [ from: "${this.groupId}.service.dto.${entityName}DTO", to: "${this.groupId}.dto.${entityName}DTO"] ,
                // 移动 Resource 的命名空间
                [ from:"${this.groupId}.web.rest.${entityName}Resource", to:"${this.groupId}.rest.${entityName}Resource"] ,
                // 移动 Mapper 的命名空间
                [ from: "${this.groupId}.service.mapper.${entityName}Mapper", to:"${this.groupId}.mapper.${entityName}Mapper"] ,
                // 将 domain 改造成 BO DTO
                [ from: "${this.groupId}.domain.${entityName}", to:"${projectGroupId}.application.service.dto.${boName}DTO"] ,
                // 将 repository 改造成 BO Service
                [ from: "${this.groupId}.repository.${entityName}Repository", to:"${projectGroupId}.application.service.${boName}Service"]
        ], files){
            replaceText(/\s+extends\s+EntityMapper\b/, " extends ${frameworkGroupId}.service.mapper.EntityMapper".toRegexp(), "gm", files)
            replaceText(/import\s+${groupId.toRegexp()}\.domain\.\*\s*;/, /import ${projectGroupId.toRegexp()}\.application\.service\.dto\.\*;/, "gm", files)
        }

        assert "$tempSrcJavaDir/${groupId.packageToPath()}/mapper/${entityName}Mapper.java".isFile()
        assert "$tempSrcJavaDir/${groupId.packageToPath()}/dto/${entityName}DTO.java".isFile()
        assert "$tempSrcJavaDir/${groupId.packageToPath()}/rest/${entityName}Resource.java".isFile()
        assert "$tempSrcJavaDir/${projectGroupId.packageToPath()}/application/service/${boName}Service.java".isFile()
        assert "$tempSrcJavaDir/${projectGroupId.packageToPath()}/application/service/dto/${boName}DTO.java".isFile()

        // 提取文件到 web 工程
        def subdir1 = "${subProjectDir}/${artifactId.dashCase()}-web/src/main/java/"
        copyAndReplaceFiles(subdir1, [
                [dir: "$tempSrcJavaDir", includes: [
                        "${this.groupId}.dto.${entityName}DTO".packageToPath() + ".java",
                        "${this.groupId}.rest.${entityName}Resource".packageToPath() + ".java",
                        "${this.groupId}.mapper.${entityName}Mapper".packageToPath() + ".java"
                ]]
        ])

        assert "$subdir1/${groupId.packageToPath()}/dto/${entityName}DTO.java".isFile()
        assert "$subdir1/${groupId.packageToPath()}/rest/${entityName}Resource.java".isFile()
        assert "$subdir1/${groupId.packageToPath()}/mapper/${entityName}Mapper.java".isFile()

        assert !("$subdir1/${groupId.packageToPath()}/rest/${entityName}Resource.java".toFile().text ==~ "${this.groupId}.repository.${entityName}Repository".toRegexp())

    }

    // 解析出组件 -list, -table, -editor, -viewer 以及 -directive 后缀的同名，共 8 个
    void extractComponents(entityName, entityOptions,  relationNames, reverseRelationNames, absfiles){
        // 先根据实体的 annotation 处理文件内容
        callJavaAction(entityName, entityOptions, relationNames, reverseRelationNames, absfiles)

        def parentDir1 = "${tempSrcWebDir}/app/entities"
        def relFiles = absfiles.findAll{ it.absolutePath.startsWith(parentDir1) && it.exists() }.collect{File f ->
            "${f.absolutePath.relativePath(parentDir1)}"
        }
        def files = [
                [dir: "$parentDir1", includes: relFiles]
        ]
        // 提取文件到 web 工程
        def subdir1 = "${subProjectDir}/${artifactId.dashCase()}-web/src/main/webapp/app/entities"
        copyAndReplaceFiles(subdir1, files, [
                [match:/\.module\('\w+'\)/, replace:".module('${frameworkArtifactId.camelCase().lowerFirst()}App')", flags:"gm"]
        ])
    }

    void postProcess(){
        super.postProcess()

        // 创建 pom 文件
        copyPomXml("$targetDir/pom.xml", "${subProjectDir}/${artifactId.dashCase()}-web/pom.xml",
                [
                        "parent/groupId" : "$projectGroupId",
                        "parent/artifactId" : "${this.artifactId}-parent",
                        "parent/version" : "$version",
                        "parent/relativePath" : "../${this.artifactId}-parent",

                        "groupId": "$projectGroupId",
                        "artifactId": "${artifactId.dashCase()}-web",
                        "packaging": "jar",
                        "name": "$artifactId web project"
                ], [
                        "org.mapstruct:mapstruct-jdk8:\$"+"{mapstruct.version}",
                        "io.github.jhipster:jhipster:\$"+"{jhipster.server.version}",
                        "io.dropwizard.metrics:metrics-annotation:\$"+"{dropwizard-metrics.version}",
                        "${frameworkGroupId}:framework-util:$version", // 依赖 framework-util
                        "$projectGroupId:${artifactId.dashCase()}-application-service:$version"// 依赖 BO Service
                ]
        ){ pom ->
            pom.remove(pom.properties)
            pom.remove(pom.dependencies)
            pom.remove(pom.build)
            pom.remove(pom.profiles)
        }

        addPomModule("$subProjectDir/pom.xml", "${artifactId.dashCase()}-web")
    }
}

//new VoGenerator("/Users/jiangjianbo/work/tech/powergen/powergen-test").generate()
//System.exit(0)


class BoGenerator extends AnalysisWithActionGenerator{

    public BoGenerator(String baseDir){
        super(baseDir, "bo", "application")
    }

    void fixJdl(def fixJdlFile){
        // 删除 bo.jdl 中，所有的 dto， service impl 设置，重新添加一个标准的
        ant.replaceregexp(match: /^\s*dto\s+.+\s+with\s+mapstruct.*$/, replace: "", flags: "m"){
            fileset(file: "$fixJdlFile")
        }

        ant.replaceregexp(match: /^\s*service\s+.+\s+with\s+serviceImpl.*$/, replace: "", flags: "m"){
            fileset(file: "$fixJdlFile")
        }

        ant.echo(file: "$fixJdlFile", message: """
dto * with mapstruct
service * with serviceImpl
        """, append: true)

    }


    def listEntityFiles(String entityName){
        String entDir = "${tempSrcJavaDir}/${groupId.packageToPath()}"

        [
            "domain": ".java", // Business 对象定义
            "service": "Service.java",
            "service/impl": "ServiceImpl.java", // 提供外部调用的接口，使用 BO DTO 作为参数，中间把 BO 传递给 repository
            "service/dto": "DTO.java",
            "repository": "Repository.java",
            "service/mapper": "Mapper.java" // 负责将 BO DTO 转化为 BO 对象
        ].collect { ent ->
            def f = "$entDir/${ent.key}/${entityName}${ent.value}".toFile()
            println( f )
            f.isFile() ? f : null
        }.findAll { it != null }
    }

    void processEachEntity(String entityName, def entityOptions, def relationNames, def reverseRelationNames, def absfiles){
        println("    ${entityOptions}")
        println("    ${relationNames}")
        println("    ${reverseRelationNames}")
        println("    ${absfiles.size()}: ${absfiles}")

        // 先根据实体的 annotation 处理文件内容
        callJavaAction(entityName, entityOptions, relationNames, reverseRelationNames, absfiles)

        def parentDir1 = "${tempSrcJavaDir}/${groupId.packageToPath()}"
        def relFiles = absfiles.findAll{ it.absolutePath.startsWith(parentDir1) && it.exists() }.collect{File f ->
            "${f.absolutePath.relativePath(parentDir1)}"
        }
        def files = [
                [dir: "$parentDir1", includes: relFiles]
        ]

        // 将 repository 改造成 po 的 repository
        assert "$tempSrcJavaDir/${groupId.packageToPath()}/repository/${entityName}Repository.java".isFile()
        replaceClass("$tempSrcJavaDir", [
                [from: "${this.groupId}.service.mapper.EntityMapper", to: "${frameworkGroupId}.service.mapper.EntityMapper"],
                [ from:"${this.groupId}.domain.AbstractAuditingEntity", to: "${projectGroupId}.application.domain.AbstractAuditingEntity"],
                [ from:"${this.groupId}.repository.${entityName}Repository", to: "${projectGroupId}.repository.${entityName}Repository"]
        ], files){
            replaceText(/\s+extends\s+EntityMapper\b/, " extends ${frameworkGroupId}.service.mapper.EntityMapper".toRegexp(), "gm", files)
        }
        assert "$tempSrcJavaDir/${projectGroupId.packageToPath()}/repository/${entityName}Repository.java".isFile()

        def domainDir = "${subProjectDir}/${artifactId.dashCase()}-domain/src/main/java/"
        // 清除 domain 的所有 jpa 标记
        def domainFiles = [
                [dir: "$tempSrcJavaDir", includes: [
                        "${this.groupId}.domain.${entityName}".packageToPath() + ".java"
                ]]
        ]
        replaceText(/(^\s*@[^O].+[\r\n]+)+^(\s*((public)|(private)))/, "\\2", "gm", domainFiles)
        replaceText(/(^\s*@.+[\r\n]+)+?^\s*@JoinTable(.+[\r\n]+)+?(^\s*private)/, "\\3", "gm", domainFiles)
        replaceText(/(^\s*@.+[\r\n]+)+?(^\s*private)/, "\\2", "gm", domainFiles)

        // 提取文件到 domain 工程
        copyAndReplaceFiles(domainDir, domainFiles)
        assert "$domainDir/${groupId.packageToPath()}/domain/${entityName}.java".isFile()

        // 提取文件到 application-service 工程
        def serviceDir = "${subProjectDir}/${artifactId.dashCase()}-application-service/src/main/java/"
        copyAndReplaceFiles(serviceDir, [
                [dir: "$tempSrcJavaDir", includes: [
                        "${this.groupId}.service.${entityName}Service".packageToPath() + ".java",
                        "${this.groupId}.service.dto.${entityName}DTO".packageToPath() + ".java"
                ]]
        ])
        assert "$serviceDir/${groupId.packageToPath()}/service/${entityName}Service.java".isFile()
        assert "$serviceDir/${groupId.packageToPath()}/service/dto/${entityName}DTO.java".isFile()

        // 提取文件到 application-service-impl 工程
        def implDir = "${subProjectDir}/${artifactId.dashCase()}-application-service-impl/src/main/java/"
        copyAndReplaceFiles(implDir, [
                [dir: "$tempSrcJavaDir", includes: [
                        "${this.groupId}.service.impl.${entityName}ServiceImpl".packageToPath() + ".java",
                        "${this.groupId}.service.mapper.${entityName}Mapper".packageToPath() + ".java"
                ]]
        ])
        assert "$implDir/${groupId.packageToPath()}/service/impl/${entityName}ServiceImpl.java".isFile()
        assert "$implDir/${groupId.packageToPath()}/service/mapper/${entityName}Mapper.java".isFile()
    }

    void postProcess(){
        super.postProcess()

        // 复制 liqubase 用于描述 实体 的配置文件
        ant.copy(todir: "${subProjectDir}/${artifactId.dashCase()}-domain/src/main/resources/config/liquibase/"){
            ant.fileset(dir:"$targetDir/src/main/resources/config/liquibase/"){
                ant.include(name: "changelog/*.xml")
                ant.exclude(name: "changelog/000000*.xml")
            }
        }

        ant.copy(file:"$targetDir/src/main/resources/config/liquibase/master.xml",
                tofile: "${subProjectDir}/${artifactId.dashCase()}-domain/src/main/resources/config/liquibase/${artifactId.dashCase()}-master.xml")

        // 创建 domain pom 文件
        copyPomXml("$targetDir/pom.xml", "${subProjectDir}/${artifactId.dashCase()}-domain/pom.xml",
                [
                        "parent/groupId" : "$projectGroupId",
                        "parent/artifactId" : "${this.artifactId}-parent",
                        "parent/version" : "$version",
                        "parent/relativePath" : "../${this.artifactId}-parent",

                        "groupId": "$projectGroupId",
                        "artifactId": "${artifactId.dashCase()}-domain",
                        "packaging": "jar",
                        "name": "$artifactId domain project"
                ], [
                        "io.swagger:swagger-annotations:\$"+"{swagger-annotations.version}",
                        "org.webjars:webjars-locator:\$"+"{webjars-locator.version}",
                        "org.kohsuke.metainf-services:metainf-services:\$"+"{metainf-services.version}",
                        "${frameworkGroupId}:framework-util:$version"// 依赖 framework-util
                ]
        ){ pom ->
            pom.remove(pom.properties)
            pom.remove(pom.dependencies)
            pom.remove(pom.build)
            pom.remove(pom.profiles)
        }

        // 创建 service 定义 pom 文件
        copyPomXml("$targetDir/pom.xml", "${subProjectDir}/${artifactId.dashCase()}-application-service/pom.xml",
                [
                        "parent/groupId" : "$projectGroupId",
                        "parent/artifactId" : "${this.artifactId}-parent",
                        "parent/version" : "$version",
                        "parent/relativePath" : "../${this.artifactId}-parent",

                        "groupId": "$projectGroupId",
                        "artifactId": "${artifactId.dashCase()}-application-service",
                        "packaging": "jar",
                        "name": "$artifactId application service project"
                ], [
                        "${frameworkGroupId}:framework-util:$version", // 依赖 framework-util
                        "$projectGroupId:${artifactId.dashCase()}-domain:$version"// 依赖 domain
                ]
        ){ pom ->
            pom.remove(pom.properties)
            pom.remove(pom.dependencies)
            pom.remove(pom.build)
            pom.remove(pom.profiles)
        }

        // 创建 service impl pom 文件
        copyPomXml("$targetDir/pom.xml", "${subProjectDir}/${artifactId.dashCase()}-application-service-impl/pom.xml",
                [
                        "parent/groupId" : "$projectGroupId",
                        "parent/artifactId" : "${this.artifactId}-parent",
                        "parent/version" : "$version",
                        "parent/relativePath" : "../${this.artifactId}-parent",

                        "groupId": "$projectGroupId",
                        "artifactId": "${artifactId.dashCase()}-application-service-impl",
                        "packaging": "jar",
                        "name": "$artifactId application service implement project"
                ], [
                        "org.mapstruct:mapstruct-jdk8:\$"+"{mapstruct.version}",
                        "io.github.jhipster:jhipster:\$"+"{jhipster.server.version}",
                        "io.dropwizard.metrics:metrics-annotation:\$"+"{dropwizard-metrics.version}",
                        "${frameworkGroupId}:framework-util:$version", // 依赖 framework-util
                        "$projectGroupId:${artifactId.dashCase()}-domain:$version", // domain
                        "$projectGroupId:${artifactId.dashCase()}-application-service:$version", // 依赖 BO Service
                        "$projectGroupId:${artifactId.dashCase()}-repository:$version"// 依赖 BO Service
                ]
        ){ pom ->
            pom.remove(pom.properties)
            pom.remove(pom.dependencies)
            pom.remove(pom.build)
            pom.remove(pom.profiles)
        }

        addPomModule("$subProjectDir/pom.xml", "${artifactId.dashCase()}-domain")
        addPomModule("$subProjectDir/pom.xml", "${artifactId.dashCase()}-application-service")
        addPomModule("$subProjectDir/pom.xml", "${artifactId.dashCase()}-application-service-impl")
    }

}

//new BoGenerator("/Users/jiangjianbo/work/tech/powergen/adam2-demo").generate()
//System.exit(0)


class PoGenerator extends AnalysisWithActionGenerator{

    public PoGenerator(String baseDir){
        super(baseDir, "po", "repository")
    }

    void fixJdl(def fixJdlFile){
        // 删除 po.jdl 中，所有的 dto， service impl 设置，重新添加一个标准的
        ant.replaceregexp(match: /^\s*dto\s+.+\s+with\s+mapstruct.*$/, replace: "", flags: "m"){
            fileset(file: "$fixJdlFile")
        }

        ant.replaceregexp(match: /^\s*service\s+.+\s+with\s+serviceImpl.*$/, replace: "", flags: "m"){
            fileset(file: "$fixJdlFile")
        }

        ant.echo(file: "$fixJdlFile", message: """
dto * with mapstruct
service * with serviceImpl
        """, append: true)

    }

    def listEntityFiles(String entityName){
        String entDir = "${tempSrcJavaDir}/${groupId.packageToPath()}"

        [
                "domain": ".java", // PO 直接使用这个做存储对象
                "repository": "Repository.java",
                "repository/search":"SearchRepository.java", // 后台存储功能实现
                "service": "Service.java",
                "service/impl": "ServiceImpl.java", // 对 Business 层外暴露调用接口，接受 BO对象
                "service/dto": "DTO.java",
                "service/mapper": "Mapper.java" // 负责将 BO 对象 转化成 PO 层的 domain 对象
        ].collect { ent ->
            def f = "$entDir/${ent.key}/${entityName}${ent.value}".toFile()
            println( f )
            f.isFile() ? f : null
        }.findAll { it != null }
    }

    void processEachEntity(String entityName, def entityOptions, def relationNames, def reverseRelationNames, def absfiles){
        println("    ${entityOptions}")
        println("    ${relationNames}")
        println("    ${reverseRelationNames}")
        println("    ${absfiles.size()}: ${absfiles}")

        // 先根据实体的 annotation 处理文件内容
        callJavaAction(entityName, entityOptions, relationNames, reverseRelationNames, absfiles)

        def parentDir1 = "${tempSrcJavaDir}/${groupId.packageToPath()}"
        def relFiles = absfiles.findAll{ it.absolutePath.startsWith(parentDir1) }.collect{File f ->
            "${f.absolutePath.relativePath(parentDir1)}"
        }
        def files = [
                [dir: "$parentDir1", includes: relFiles]
        ]

        String boName = entityOptions["pg-entity"] ?: null

        assert "$tempSrcJavaDir/${groupId.packageToPath()}/repository/${entityName}Repository.java".isFile()
        assert "$tempSrcJavaDir/${groupId.packageToPath()}/domain/${entityName}.java".isFile()
        if( boName != null ) {
            assert "$tempSrcJavaDir/${groupId.packageToPath()}/service/dto/${entityName}DTO.java".isFile()
            assert "$tempSrcJavaDir/${groupId.packageToPath()}/service/${entityName}Service.java".isFile()
            assert "$tempSrcJavaDir/${groupId.packageToPath()}/service/impl/${entityName}ServiceImpl.java".isFile()
            assert "$tempSrcJavaDir/${groupId.packageToPath()}/service/mapper/${entityName}Mapper.java".isFile()
        }

        def classMaps = [
                // 移动 repository 到 新的 package
                [ from: "${this.groupId}.repository.${entityName}Repository", to:"${this.groupId}.access.${entityName}Access"],

                // 移动 repository 的 domain 到 新的 package，作为 PO
                [ from:  "${this.groupId}.domain.${entityName}", to:"${this.groupId}.po.${entityName}"]
        ]

        if( boName != null ) {
            classMaps += [
                    [from: "${this.groupId}.service.mapper.EntityMapper", to: "${frameworkGroupId}.service.mapper.EntityMapper"],
                    // 将 PO DTO 修改为 application 的 BO Domain
                    [ from:  "${this.groupId}.service.dto.${entityName}DTO", to:"${projectGroupId}.application.domain.${boName}"],
                    // 将 PO service 修改为 repository
                    [ from:  "${this.groupId}.service.${entityName}Service", to:"${this.groupId}.${boName}Repository"],
                    // 将 PO service impl 修改为 repository impl
                    [ from: "${this.groupId}.service.impl.${entityName}ServiceImpl",to: "${this.groupId}.impl.${boName}RepositoryImpl"],
                    // 移动 Mapper 的命名空间
                    [ from:  "${this.groupId}.service.mapper.${entityName}Mapper", to:"${this.groupId}.mapper.${entityName}Mapper"]
            ]
        }

        replaceClass("$tempSrcJavaDir", classMaps, files ){
                replaceText(/import\s+${groupId.toRegexp()}\.domain\.\*\s*;/, /import ${groupId.toRegexp()}\.po\.\*;/, "gm", files)
                replaceText(/\s+extends\s+EntityMapper\b/, " extends ${frameworkGroupId}.service.mapper.EntityMapper".toRegexp(), "gm", files)
        }

        assert "$tempSrcJavaDir/${groupId.packageToPath()}/access/${entityName}Access.java".isFile()
        assert "$tempSrcJavaDir/${groupId.packageToPath()}/po/${entityName}.java".isFile()

        if( boName != null ) {
            assert "$tempSrcJavaDir/${projectGroupId.packageToPath()}/application/domain/${boName}.java".isFile()
            assert "$tempSrcJavaDir/${groupId.packageToPath()}/${boName}Repository.java".isFile()
            assert "$tempSrcJavaDir/${groupId.packageToPath()}/impl/${boName}RepositoryImpl.java".isFile()
            assert "$tempSrcJavaDir/${groupId.packageToPath()}/mapper/${entityName}Mapper.java".isFile()

            // 提取文件到 repository 工程
            def repoDir = "${subProjectDir}/${artifactId.dashCase()}-repository/src/main/java/"
            copyAndReplaceFiles(repoDir, [
                    [dir: "$tempSrcJavaDir", includes: [
                            "${this.groupId}.${boName}Repository".packageToPath() + ".java"
                    ]]
            ])
            assert "$repoDir/${groupId.packageToPath()}/${boName}Repository.java".isFile()

            // 提取文件到 repository-impl 工程
            def repoImplDir = "${subProjectDir}/${artifactId.dashCase()}-repository-impl/src/main/java/"
            copyAndReplaceFiles(repoImplDir, [
                    [dir: "$tempSrcJavaDir", includes: [
                            "${this.groupId}.impl.${boName}RepositoryImpl".packageToPath() + ".java",
                            "${this.groupId}.mapper.${entityName}Mapper".packageToPath() + ".java"
                    ]]
            ])

            assert "$repoImplDir/${groupId.packageToPath()}/impl/${boName}RepositoryImpl.java".isFile()
            assert "$repoImplDir/${groupId.packageToPath()}/mapper/${entityName}Mapper.java".isFile()

        }

        def subdir1 = "${subProjectDir}/${artifactId.dashCase()}-repository-impl/src/main/java/"
        copyAndReplaceFiles(subdir1, [
                [dir: "$tempSrcJavaDir", includes: [
                        "${this.groupId}.po.${entityName}".packageToPath() + ".java",
                        "${this.groupId}.access.${entityName}Access".packageToPath() + ".java"
                ]]
        ])


        assert "$subdir1/${groupId.packageToPath()}/po/${entityName}.java".isFile()
        assert "$subdir1/${groupId.packageToPath()}/access/${entityName}Access.java".isFile()

    }

    void postProcess(){
        super.postProcess()

        // 添加 repository pom 文件
        copyPomXml("$targetDir/pom.xml", "${subProjectDir}/${artifactId.dashCase()}-repository/pom.xml", [
                "parent/groupId" : "$projectGroupId",
                "parent/artifactId" : "${this.artifactId}-parent",
                "parent/version" : "$version",
                "parent/relativePath" : "../${this.artifactId}-parent",

                "groupId": "$projectGroupId",
                "artifactId": "${artifactId.dashCase()}-repository",
                "packaging": "jar",
                "name": "$artifactId repository project"
        ], [
                "${projectGroupId}:${artifactId.dashCase()}-domain:$version"
        ]){ pom ->
            pom.remove(pom.properties)
            pom.remove(pom.dependencies)
            pom.remove(pom.build)
            pom.remove(pom.profiles)
        }

        // 创建 Repository 的自动注册扫描文件
        def upperArtifactId = artifactId.camelCase().replaceAll("Plugin", "").upperFirst()
        System.out.println("artifact name = ${upperArtifactId}")
        def dbconf = new File("${subProjectDir}/${artifactId.dashCase()}-repository-impl/src/main/java/${groupId.packageToPath()}/${upperArtifactId}DatabaseConfiguration.java")
        if( dbconf.exists() == false ) {
            dbconf.withPrintWriter {
                it.println("""
        package ${this.groupId};

        @org.springframework.context.annotation.Configuration
        @org.springframework.data.jpa.repository.config.EnableJpaRepositories("${this.groupId}")
        @org.springframework.data.jpa.repository.config.EnableJpaAuditing(auditorAwareRef = "springSecurityAuditorAware")
        @org.springframework.transaction.annotation.EnableTransactionManagement
        public class ${upperArtifactId}DatabaseConfiguration {
        }
        """)
            }
        }

        // 添加 repository impl pom 文件
        copyPomXml("$targetDir/pom.xml", "${subProjectDir}/${artifactId.dashCase()}-repository-impl/pom.xml", [
                "parent/groupId" : "$projectGroupId",
                "parent/artifactId" : "${this.artifactId}-parent",
                "parent/version" : "$version",
                "parent/relativePath" : "../${this.artifactId}-parent",

                "groupId": "$projectGroupId",
                "artifactId": "${artifactId.dashCase()}-repository-impl",
                "packaging": "jar",
                "name": "$artifactId repository implement project"
        ], [
                "org.mapstruct:mapstruct-jdk8:\$"+"{mapstruct.version}",
                "${projectGroupId}:${artifactId.dashCase()}-domain:$version",
                "$projectGroupId:${artifactId.dashCase()}-repository:$version"
        ]){ pom ->
            pom.remove(pom.properties)
            pom.remove(pom.dependencies)
            pom.remove(pom.build)
            pom.remove(pom.profiles)
        }

        addPomModule("$subProjectDir/pom.xml", "${artifactId.dashCase()}-repository")
        addPomModule("$subProjectDir/pom.xml", "${artifactId.dashCase()}-repository-impl")
    }

}

//new PoGenerator("/Users/jiangjianbo/work/tech/powergen/powergen-test").generate()
//System.exit(0)

//String frameworkPackage="cn.gyxr.saas"
//String frameworkName="gyxrframe"
//String homeDir="/Users/jiangjianbo/work/tech/powergen/powergen-test"
//String homeDir="/Users/jiangjianbo/work/tech/powergen/adam2-demo"
//
//frameworkPackage="cn.gyxr.saas.frame"
//frameworkName="EdenFramework"
//
//parentGroupId="org.springframework.boot"
//parentArtifactId="spring-boot-starter-parent"
//parentVersion="1.5.3.RELEASE"

Generator.frameworkGroupId = frameworkPackage
Generator.frameworkArtifactId = frameworkName
Generator.parentGroupId = parentGroupId
Generator.parentArtifactId = parentArtifactId
Generator.parentVersion = parentVersion

// 增加UI层的目的是啥？
[
    /*new UILayer(), */
//    new FrameworkGenerator(homeDir)
    new FrameworkGenerator(homeDir),
    new VoGenerator(homeDir),
    new BoGenerator(homeDir),
    new PoGenerator(homeDir)
].each{
    it.generate()
}







