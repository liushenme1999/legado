#!/usr/bin/env python3
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "rssSource_verified.json"

MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Mobile Safari/537.36"
)
DESKTOP_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

RSS_ITEM = {
    "ruleArticles": "//item",
    "ruleTitle": "title@text",
    "ruleLink": "link@text",
    "rulePubDate": "pubDate@text",
    "ruleDescription": "",
    "ruleImage": "",
    "singleUrl": False,
    "enableJs": True,
    "loadWithBaseUrl": True,
    "enabledCookieJar": False,
}

ATOM_ENTRY = {
    **RSS_ITEM,
    "ruleArticles": "//entry",
    "ruleLink": "link@href",
    "rulePubDate": "published@text||updated@text",
}

RSS_SOURCES = {
    "IT之家": {
        "sourceUrl": "https://www.ithome.com/rss/",
        "sourceGroup": "科技",
        "ruleContent": "id.paragraph@html||class.post_content@html",
    },
    "少数派": {
        "sourceUrl": "https://sspai.com/feed",
        "sourceGroup": "科技生活",
        "ruleContent": "class.article-body@html||class.article__main__content@html",
    },
    "机核GCORES": {
        "sourceUrl": "https://www.gcores.com/rss",
        "sourceGroup": "游戏",
        "ruleContent": "class.md-RichEditor-root@html||class.newsPage_story@html",
    },
    "爱范儿": {
        "sourceUrl": "https://www.ifanr.com/feed",
        "sourceGroup": "科技",
        "ruleContent": "class.c-article-content@html",
    },
    "极客公园": {
        "sourceUrl": "https://www.geekpark.net/rss",
        "sourceGroup": "科技",
        "ruleLink": "link@text@js:result.replace(/^http:/,'https:')",
        "ruleContent": "class.article-content@html||class.article-body@html",
    },
    "36氪": {
        "sourceUrl": "https://www.36kr.com/feed",
        "sourceGroup": "财经",
        "header": json.dumps({"User-Agent": MOBILE_UA}, ensure_ascii=False, separators=(",", ":")),
        "ruleLink": "link@text@js:result.replace(/36kr\\.com/,'m.36kr.com').replace(/\\?.*$/,'')",
        "ruleContent": "id.body-content@html||class.body-content-wrapper-main@html",
    },
    "小众软件": {
        "sourceUrl": "https://www.appinn.com/feed/",
        "sourceGroup": "软件",
        "ruleContent": "class.post-single-content@html",
    },
    "异次元软件世界": {
        "sourceUrl": "https://www.iplaysoft.com/feed",
        "sourceGroup": "软件",
        "ruleContent": "class.entry-content@html",
    },
    "联合国新闻": {
        "sourceUrl": "https://news.un.org/feed/subscribe/zh/news/all/rss.xml",
        "sourceGroup": "国际新闻",
        "ruleLink": "link@text@js:result.replace('/feed/view/zh/story/','/zh/story/')",
        "ruleContent": "id.block-views-block-content-fields-block-body@html",
    },
    "NPR国际新闻": {
        "sourceUrl": "https://feeds.npr.org/1004/rss.xml",
        "sourceGroup": "国际新闻",
        "ruleContent": "class.storytext@html||id.storytext@html",
    },
    "Hacker News": {
        "sourceUrl": "https://hnrss.org/frontpage",
        "sourceGroup": "开发",
        "ruleDescription": "description@html",
        "ruleContent": "",
    },
    "GitHub Blog": {
        "sourceUrl": "https://github.blog/feed/",
        "sourceGroup": "开发",
        "ruleContent": "class.post__content@html",
    },
    "Kotlin Blog": {
        "sourceUrl": "https://feeds.feedburner.com/kotlin",
        "sourceGroup": "开发",
        "ruleContent": "class.content.js-toc-content@html",
    },
    "Cloudflare Blog": {
        "sourceUrl": "https://blog.cloudflare.com/rss/",
        "sourceGroup": "开发",
        "ruleContent": "class.article-content@html",
    },
    "FreeBuf网络安全": {
        "sourceUrl": "https://www.freebuf.com/rss",
        "sourceGroup": "网络安全",
        "header": json.dumps(
            {"User-Agent": DESKTOP_UA}, ensure_ascii=False, separators=(",", ":")
        ),
        "ruleContent": "class.content-detail@html",
    },
    "Nature": {
        "sourceUrl": "https://www.nature.com/nature.rss",
        "sourceGroup": "科学",
        "ruleLink": "link@text",
        "rulePubDate": "",
        "ruleContent": "class.c-article-body@html||class.c-article-main-column@html",
    },
    "NASA": {
        "sourceUrl": "https://www.nasa.gov/rss/dyn/breaking_news.rss",
        "sourceGroup": "科学",
        "ruleContent": "class.entry-content@html",
    },
    "MIT科技评论": {
        "sourceUrl": "https://www.technologyreview.com/feed/",
        "sourceGroup": "科技",
        "ruleContent": "id.content--body@html",
    },
    "Wired": {
        "sourceUrl": "https://www.wired.com/feed/tag/ai/latest/rss",
        "sourceGroup": "科技",
        "ruleContent": (
            "class.article-body__content@html@js:"
            "doc=org.jsoup.Jsoup.parse(result);"
            "doc.select('[data-testid=action-bar-wrapper]').remove();"
            "doc.body().html()"
        ),
    },
    "GitHub Trending": {
        "sourceUrl": "https://mshibanami.github.io/GitHubTrendingRSS/daily/all.xml",
        "sourceGroup": "开发",
        "rulePubDate": "",
        "ruleDescription": "description@html",
        "ruleContent": "",
    },
}

NEW_RSS_SOURCES = {
    "阮一峰的网络日志": {
        "sourceUrl": "https://www.ruanyifeng.com/blog/atom.xml",
        "sourceGroup": "开发",
        "sourceIcon": "https://www.ruanyifeng.com/favicon.ico",
        "atom": True,
        "ruleContent": "id.main-content@html",
    },
    "V2EX": {
        "sourceUrl": "https://www.v2ex.com/index.xml",
        "sourceGroup": "社区",
        "sourceIcon": "https://www.v2ex.com/static/img/icon_rayps_64.png",
        "atom": True,
        "ruleContent": "class.topic_content@html",
    },
    "Hugging Face Blog": {
        "sourceUrl": "https://huggingface.co/blog/feed.xml",
        "sourceGroup": "人工智能",
        "sourceIcon": "https://huggingface.co/favicon.ico",
        "ruleContent": "class.blog-content@html",
    },
    "Google AI Blog": {
        "sourceUrl": "https://blog.google/technology/ai/rss/",
        "sourceGroup": "人工智能",
        "sourceIcon": "https://blog.google/favicon.ico",
        "ruleContent": "class.article-container@html",
    },
    "Android Developers Blog": {
        "sourceUrl": "https://android-developers.googleblog.com/feeds/posts/default",
        "sourceGroup": "开发",
        "sourceIcon": "https://developer.android.com/favicon.ico",
        "atom": True,
        "ruleContent": "class.devsite-article-body@html",
    },
    "Solidot": {
        "sourceUrl": "https://www.solidot.org/index.rss",
        "sourceGroup": "科技",
        "sourceIcon": "https://www.solidot.org/favicon.ico",
        "header": json.dumps(
            {"User-Agent": DESKTOP_UA},
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        "ruleContent": "id.main@html",
    },
    "TechCrunch": {
        "sourceUrl": "https://techcrunch.com/feed/",
        "sourceGroup": "科技",
        "sourceIcon": "https://techcrunch.com/favicon.ico",
        "ruleContent": "class.entry-content@html||class.wp-block-post-content@html",
    },
}

REMOVE = {
    "Science",
    "Ars Technica",
    "推图尤物",
    "嗨漫之家",
    "在线游戏",
    "摄影图库",
    "BleepingComputer",
}


def repair_custom(source: dict) -> dict:
    name = source["sourceName"]
    if name == "果核剥壳":
        fields = "link,title,date,excerpt"
        api = f"https://www.ghxi.com/wp-json/wp/v2/posts?per_page=20&_fields={fields}"
        source.update(
            {
                "sourceUrl": api,
                "sortUrl": "\n".join(
                    [
                        f"最新::{api}",
                        f"AI::{api}&categories=180",
                        f"安卓::{api}&categories=4,30,89,109,20,114",
                        f"电脑::{api}&categories=53,22,15,39,101,119,164,165,176,177,76,172,77,78,122",
                        f"圈内新闻::{api}&categories=125",
                        f"搜索::{api}&search={{{{source.getVariable()}}}}",
                    ]
                ),
                "singleUrl": False,
                "ruleArticles": "$[*]",
                "ruleTitle": "$.title.rendered",
                "ruleLink": "$.link",
                "rulePubDate": "$.date",
                "ruleDescription": "",
                "ruleImage": "",
                "ruleContent": "class.entry-content@html",
                "enabledCookieJar": False,
                "enableJs": True,
                "loadWithBaseUrl": True,
            }
        )
        source.pop("ruleNextPage", None)
    elif name == "道藏":
        source.update(
            {
                "ruleContent": "id.content3@html",
                "ruleDescription": "",
                "enabledCookieJar": False,
            }
        )
    elif name == "晋江书单":
        source.update(
            {
                "sourceUrl": (
                    "https://app.jjwxc.org/app.jjwxc/android/reading/"
                    "BookListFindBook/square?versionCode=357&rec_new_offset=0"
                    "&find_new_offset=0&rec_edit_offset=0&page=1&pageSize=10"
                ),
                "sortUrl": (
                    "书单广场::https://app.jjwxc.org/app.jjwxc/android/reading/"
                    "BookListFindBook/square?versionCode=357&rec_new_offset=0"
                    "&find_new_offset=0&rec_edit_offset=0&page=1&pageSize=10\n\n"
                    "搜索书单::https://app.jjwxc.org/app.jjwxc/android/reading/"
                    "BookListSearch/search?versionCode=357&keywords={{source.getVariable()}}"
                    "&page=1&sort=fav_count\n\n"
                    "搜索百合书单::https://android.jjwxc.com/app.jjwxc/android/reading/"
                    "BookListSearch/search?versionCode=358&keywords={{source.getVariable()}}"
                    "&page=1&sort=fav_count&must_xx=3&could_xx=5"
                    "&book_num_range=0%2C252&data_type=1%2C2"
                ),
                "ruleNextPage": (
                    "<js>\n"
                    "if(/rec_new_offset/.test(baseUrl)){\n"
                    " result='https://app.jjwxc.org/app.jjwxc/android/reading/"
                    "BookListFindBook/square?versionCode=357&rec_new_offset={{$.data.rec_new_offset}}"
                    "&find_new_offset={{$.data.find_new_offset}}"
                    "&rec_edit_offset={{$.data.rec_edit_offset}}&page=1&pageSize=10';\n"
                    "}else{\n"
                    " result=baseUrl.replace(/page=\\d+/,'page='"
                    "+(Number(baseUrl.match(/page=(\\d+)/)[1])+1));\n"
                    "}\n"
                    "</js>"
                ),
                "ruleDescription": "",
                "enabledCookieJar": False,
            }
        )
    elif name == "长佩书单":
        source["enabledCookieJar"] = False
    return source


def apply_new_rss_config(source: dict, config: dict) -> dict:
    source.update(ATOM_ENTRY if config.get("atom") else RSS_ITEM)
    source.update(
        {
            "sourceUrl": config["sourceUrl"],
            "sourceGroup": config["sourceGroup"],
            "sourceIcon": config["sourceIcon"],
            "ruleContent": config["ruleContent"],
            "ruleDescription": "",
        }
    )
    if "header" in config:
        source["header"] = config["header"]
    return source


def main() -> None:
    with TARGET.open(encoding="utf-8") as fp:
        sources = json.load(fp)

    result = []
    existing_names = set()
    for source in sources:
        name = source["sourceName"]
        existing_names.add(name)
        if name in REMOVE:
            continue
        if name in RSS_SOURCES:
            source.update(RSS_ITEM)
            source.update(RSS_SOURCES[name])
        elif name in NEW_RSS_SOURCES:
            apply_new_rss_config(source, NEW_RSS_SOURCES[name])
        elif name in {"果核剥壳", "道藏", "晋江书单", "长佩书单"}:
            repair_custom(source)
        else:
            raise RuntimeError(f"Unhandled source: {name}")
        result.append(source)

    order = 990
    for name, config in NEW_RSS_SOURCES.items():
        if name in existing_names:
            continue
        source = {
            "articleStyle": 0,
            "customOrder": order,
            "enableJs": True,
            "enabled": True,
            "enabledCookieJar": False,
            "lastUpdateTime": 0,
            "loadWithBaseUrl": True,
            "singleUrl": False,
            "sourceName": name,
            "sourceUrl": config["sourceUrl"],
            "sourceGroup": config["sourceGroup"],
            "sourceIcon": config["sourceIcon"],
            "ruleDescription": "",
            "ruleImage": "",
            "ruleContent": config["ruleContent"],
        }
        apply_new_rss_config(source, config)
        result.append(source)
        order += 1

    names = [source["sourceName"] for source in result]
    urls = [source["sourceUrl"] for source in result]
    if len(names) != len(set(names)):
        raise RuntimeError("Duplicate source names")
    if len(urls) != len(set(urls)):
        raise RuntimeError("Duplicate source URLs")

    with TARGET.open("w", encoding="utf-8") as fp:
        json.dump(result, fp, ensure_ascii=False, indent=2, sort_keys=True)
        fp.write("\n")
    print(f"Wrote {len(result)} sources to {TARGET}")


if __name__ == "__main__":
    main()
