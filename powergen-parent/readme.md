视图层对象的命名规范

界面操作基本元素包括：
1. 创建（Create）：创建新增一个对象，需要包含对象的全部信息
2. 编辑（Edit）：编辑一个存在的对象，需要包含对象的全部信息
3. 查询（Search）：通过条件搜索对象，并且列表列出附和条件的对象。一般只需要列举对象的简要信息（Brief）
4. 删除（Delete）：删除一个对象。不需要查看对象的信息，除了ID之外
5. 概要查看（Brief）：只读形式查看对象概要属性
5. 详细查看（Info）：只读形式查看对象属性，包含对象的全部信息

因此，一个独立的界面对象，默认是 XxxView 格式，表示全量信息， XxxBriefView 表示简要信息， XxxSearchView 表示查找条件对象。
如果有对象比较特殊，例如用户修改密码，则使用“对象名+动词+View”的格式，UserChangePasswordView。

在操作上，有些界面对象是不需要完整的操作的，例如搜索对象XxxSearchView就只有Edit状态的界面。

-detail 对应只读界面
s.html 对应列表
-dialog 对应编辑
-delete-dialog 对应删除确认，只有一个id显示


视图层对象存在相互覆盖的情况，通过在 entity的注释里加入 func:create,edit,brief,detail,delete



