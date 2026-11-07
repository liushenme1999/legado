# 书籍文件导入解析

* BaseLocalBookParse.kt 本地书籍解析接口
* LocalBook.kt 导入解析总入口
* TextFile.kt 解析txt
* EpubFile.kt 解析epub
* PdfFile.kt 解析pdf 纯图片形式
* UmdFile.kt 解析umd
* MobiFile.kt 解析mobi / azw / azw3
* MdFile.kt 解析markdown(.md / .markdown), 按 ATX 标题(#)切分目录