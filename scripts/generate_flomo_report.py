# -*- coding: utf-8 -*-
"""生成 flomo 浮墨笔记调研报告 docx"""

from docx import Document
from docx.shared import Pt, RGBColor, Cm
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.oxml import OxmlElement


def set_cn_font(run, font_name="微软雅黑", size=None, bold=None, color=None):
    run.font.name = font_name
    r = run._element
    rPr = r.get_or_add_rPr()
    rFonts = rPr.find(qn("w:rFonts"))
    if rFonts is None:
        rFonts = OxmlElement("w:rFonts")
        rPr.append(rFonts)
    rFonts.set(qn("w:eastAsia"), font_name)
    rFonts.set(qn("w:ascii"), font_name)
    rFonts.set(qn("w:hAnsi"), font_name)
    if size is not None:
        run.font.size = Pt(size)
    if bold is not None:
        run.bold = bold
    if color is not None:
        run.font.color.rgb = color


def add_title(doc, text):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run(text)
    set_cn_font(run, "微软雅黑", 22, True, RGBColor(0x1F, 0x3A, 0x5F))
    p.space_after = Pt(6)


def add_subtitle(doc, text):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run(text)
    set_cn_font(run, "微软雅黑", 11, False, RGBColor(0x80, 0x80, 0x80))


def add_h1(doc, text):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(18)
    p.paragraph_format.space_after = Pt(8)
    run = p.add_run(text)
    set_cn_font(run, "微软雅黑", 16, True, RGBColor(0x1F, 0x3A, 0x5F))


def add_h2(doc, text):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(12)
    p.paragraph_format.space_after = Pt(6)
    run = p.add_run(text)
    set_cn_font(run, "微软雅黑", 13, True, RGBColor(0x2C, 0x5F, 0x8A))


def add_h3(doc, text):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(8)
    p.paragraph_format.space_after = Pt(4)
    run = p.add_run(text)
    set_cn_font(run, "微软雅黑", 11.5, True, RGBColor(0x40, 0x40, 0x40))


def add_p(doc, text, size=10.5):
    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(6)
    p.paragraph_format.line_spacing = 1.5
    run = p.add_run(text)
    set_cn_font(run, "微软雅黑", size, False, RGBColor(0x33, 0x33, 0x33))


def add_bullet(doc, text, size=10.5):
    p = doc.add_paragraph(style="List Bullet")
    p.paragraph_format.space_after = Pt(3)
    p.paragraph_format.line_spacing = 1.4
    run = p.add_run(text)
    set_cn_font(run, "微软雅黑", size, False, RGBColor(0x33, 0x33, 0x33))


def add_quote(doc, text):
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Cm(1)
    p.paragraph_format.right_indent = Cm(1)
    p.paragraph_format.space_before = Pt(6)
    p.paragraph_format.space_after = Pt(6)
    p.paragraph_format.line_spacing = 1.4
    run = p.add_run(text)
    set_cn_font(run, "楷体", 10.5, False, RGBColor(0x60, 0x60, 0x60))


def add_divider(doc):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(6)
    p.paragraph_format.space_after = Pt(6)
    run = p.add_run("—" * 40)
    run.font.color.rgb = RGBColor(0xCC, 0xCC, 0xCC)
    run.font.size = Pt(8)


def build():
    doc = Document()

    # 页边距
    section = doc.sections[0]
    section.top_margin = Cm(2.2)
    section.bottom_margin = Cm(2.2)
    section.left_margin = Cm(2.4)
    section.right_margin = Cm(2.4)

    # 封面
    add_title(doc, "浮墨笔记（flomo）产品调研报告")
    add_subtitle(doc, "对标 LifeTrack 日记 App 改进的参考依据")
    add_subtitle(doc, "调研日期：2026 年 7 月 2 日")
    add_divider(doc)

    # ============ 一、产品定位与哲学 ============
    add_h1(doc, "一、产品定位与哲学")

    add_h2(doc, "1.1 开发者背景")
    add_p(doc,
          "flomo 由少楠（Plidezus）和 lightory（白光）两人联合创立，公司主体为上海仙蒂网络科技有限公司。少楠是有十年经验的产品经理，曾运营基于 Notion 的共享知识库「产品沉思录」；lightory 是全栈工程师。两人于 2020 年冬在北京望京涮羊肉时达成共识，决定做一款不追逐用户量和估值的笔记工具。团队长期保持 2 人全职，融资额至今为 0，靠订阅收入自负盈亏。")

    add_p(doc,
          "2022 年 flomo 全资收购了飞书旗下的「幕布」大纲笔记，幕布继续独立运营。少楠和白光后来还合著了《笔记的方法》一书，把产品背后的方法论沉淀成了文字。")

    add_h2(doc, "1.2 开发初衷")
    add_p(doc,
          "少楠在推荐 Notion 给同事时反复受挫：block 是什么、database 是什么，没人能搞懂。他观察身边的同事发现一个反直觉的事实，大多数人记笔记根本不分类，标题随手写个日期，极少整理归档，找东西靠搜索或时间倒推。更意外的是，很多人开小号把想法发给文件传输助手。")
    add_p(doc,
          "这让他意识到，市面上的笔记工具走偏了方向。它们要么死磕写作体验（Notion、石墨），要么死磕知识联结（RoamResearch、Obsidian），却忽略了最根本的问题：大多数人不是不想记，是记录的摩擦太大，导致输入量严重不足。于是 flomo 的形态确定为「像聊天一样」，模仿 Slack 和文件传输助手那套低摩擦输入。")

    add_h2(doc, "1.3 核心产品哲学")
    add_p(doc, "flomo 的设计哲学可以归纳为几条原则：")
    add_bullet(doc, "降低输入摩擦，提高输入动机。这是第一阶段的全部目标。")
    add_bullet(doc, "只给自己看的微博。反社交、反分享、反点赞，不做任何扭曲记录动机的激励。")
    add_bullet(doc, "关注个体而非企业协同。当资本都盯着企业 SaaS 时，flomo 选择陪伴个人用户终身的方向。")
    add_bullet(doc, "拒绝做完美的产品，把精力聚焦于用户价值的核心。设置页面可以粗糙，「修改昵称」功能可以至今没有，付费甚至一度靠第三方表单驱动。")
    add_bullet(doc, "不鼓励收藏和导入。少楠认为收藏是「收藏者谬误」，感觉收藏了就等于学会了，实际上只是信息搬运，没有加工。")

    add_p(doc,
          "产品理念受两位前人影响很大。一是德国社会学家尼克拉斯·卢曼的卡片盒笔记法，他一生写了 9 万张卡片，靠这套方法产出了 58 本书。卢曼的核心观点是「写作不是思考的结果，而是思考发生的媒介」「没有人会从零开始」「分类不重要，知识像植物一样有机生长」。二是 Andy Matuschak（前 Apple iOS 工程师、可汗学院研发负责人）的 EvergreenNote 概念，他提出「目标不是做笔记，而是有效思考」，知识工作者需要像运动员一样刻意练习基本功。")

    add_h2(doc, "1.4 目标用户群体")
    add_p(doc,
          "目标用户是有思考需求但不会成为作家的知识工作者。少楠认为「能成为作家的人少之又少，而需要思考的人却越来越多」。具体画像包括产品经理、开发者、读书人、投资研究者、学生。这类人的痛点不是如何组织文档，而是想法根本没记下来，输入量不够。")

    add_h2(doc, "1.5 与主流笔记应用的差异化定位")
    add_p(doc, "和几款主流工具相比，flomo 的差异化非常清晰：")
    add_bullet(doc,
          "对比 Notion：Notion 是 All-in-one 的数据库和文档编辑器，学习成本高，聚焦「写作」和「组织」。flomo 只聚焦「记录」，不做 block、不做 database、不做协同。")
    add_bullet(doc,
          "对比 Bear：Bear 是 Markdown 笔记，仍然有文档概念和排版需求。flomo 主动弱化排版，连富文本都不支持，标签用手动输入 # 符号而非点击。")
    add_bullet(doc,
          "对比 Apple Notes：备忘录是本地工具，没有跨平台同步和回顾体系。flomo 自建云服务全平台同步，且有每日回顾、随机漫步等主动召回机制。")
    add_bullet(doc,
          "对比 Day One：Day One 是日记应用，强结构化（每天一篇），强媒体（照片、位置）。flomo 是碎片化的卡片流，一天可以记多条，不强求完整。")
    add_bullet(doc,
          "对比印象笔记：印象笔记是收藏型工具，鼓励剪藏网页。flomo 明确反对收藏，认为那是知识搬运。")

    # ============ 二、核心功能逐一拆解 ============
    add_h1(doc, "二、核心功能逐一拆解")

    add_h2(doc, "2.1 主界面交互")
    add_p(doc,
          "主界面是一个聊天式的输入框加时间倒序的 MEMO 列表，形态接近微信对话或文件传输助手。打开 App 直接看到输入框，不需要点「新建」。这种设计的意图是消除「创建一篇文档」的心理压力，让记录像发消息一样自然。少楠观察过，多数人在 Slack 和文件传输助手里记东西时摩擦最小，所以直接借鉴了这套交互。")

    add_h2(doc, "2.2 笔记的创建、编辑、删除")
    add_p(doc,
          "一条笔记在 flomo 里叫一个 MEMO，是产品最小的单位。创建就是在输入框打字，支持文字、拍照、扫描。编辑可以事后修改内容和标签。删除走回收站。整个编辑器只支持少量 Markdown 语法（比如加粗、列表），不支持图文混排，不支持改字体颜色。这是刻意的限制，目的是消除排版的可能性，让用户把注意力放在文字本身。")

    add_h2(doc, "2.3 标签与分类系统")
    add_p(doc,
          "flomo 没有文件夹，只有标签。标签用 # 开头手动输入，支持多级层级，格式是 #父标签/子标签。一条 MEMO 可以打多个标签。这种设计来自卢曼的观点：「我们的大脑不按分类工作，知识像植物一样有机生长，传统分类无法满足这种生长状态」。")
    add_p(doc,
          "标签的争议很大。好评者认为多级标签灵活，能动态增删重组。差评者（比如 ZOL 问答上的用户）吐槽标签功能反人类，要手动加 # 符号，不能像别的 App 点一下就加。这是 flomo 刻意的取舍：手动输入 # 是为了和文字流融为一体，输入时不离开键盘，但学习成本确实存在。后来 flomo 加了标签图标、标签置顶来缓解这个问题。")

    add_h2(doc, "2.4 搜索功能")
    add_p(doc,
          "支持全文搜索，可以按标签、按时间筛选。PRO 会员开通了双链后还能看到笔记间的引用关系。搜索的逻辑是「时间流 + 标签 + 全文」三维度定位，弱化文件夹树状结构。这是少楠观察到的用户实际行为：大多数人找东西要么搜，要么按时间倒推，很少有人去文件夹里翻。")

    add_h2(doc, "2.5 归档与回收站")
    add_p(doc,
          "flomo 有意弱化「文件夹—文件」的传统管理形式，所有笔记都放在一个时间流里，通过时间、标签、搜索来定位，而不是靠归档目录。删除的 MEMO 进回收站，可以恢复。没有传统意义上的归档功能，因为「归档」意味着要分类，而 flomo 认为分类不重要。")

    add_h2(doc, "2.6 时间线与历史")
    add_p(doc,
          "主列表本身就是时间倒序的流，这是 flomo 的核心组织方式。笔记按创建时间排列，可以按日期、按标签筛选查看。还有「那年今日」功能，通过 Widget 或控制中心入口回顾历史同日记录。这个功能的意图是让过去的记录在当下被重新看见，对抗「记了就忘」的困境。")

    add_h2(doc, "2.7 数据同步机制")
    add_p(doc,
          "flomo 不用 iCloud。lightory 在开发理念里专门解释过原因：iCloud 是 Apple Only 的技术，用了就等于抛弃 Windows 和 Android 用户，而他们想服务所有平台的用户，因为不是每个 iPhone 用户都在用 Mac。同步走自建云服务，多设备永久免费同步，实时备份。技术上采用 SSL 加密传输，单台服务器扛了很长时间（少楠戏称「祖传单核服务器」），后来才分拆到多台。ElasticSearch 至今没部署，因为要省钱。")

    add_h2(doc, "2.8 导入导出")
    add_p(doc,
          "导出支持 HTML 和 Markdown 两种格式。早期导出是 PRO 专属功能，后来出于数据可携的考虑向所有用户开放，但加了限制：每 7 天只能导出一次。这个限制是成本控制，不是产品决策。导入方面，flomo 通过 API 支持从微信读书、得到、有知有行、多看、Kindle 等平台导入笔记。但少楠明确反对「收藏式导入」，认为那是知识搬运，所以导入不是核心宣传点。")

    add_h2(doc, "2.9 主题与外观")
    add_p(doc,
          "支持深色模式和浅色模式，在「设置—偏好设置—深色模式」里切换。官方说有 6 个颜色主题。整体走少颜色、少个性的路线，符合设计哲学里「尽量少的颜色，尽量少的个性」的原则。")

    add_h2(doc, "2.10 字体与排版")
    add_p(doc,
          "用系统默认字体，不提供字体选择。排版上禁止富文本、禁止图文混排、禁止改字体颜色。这些限制都是刻意的，目的是让用户没法在排版上花时间，把精力集中在文字内容。少楠说，「许多用户反馈我们不支持 Markdown 语法、不能修改字体和颜色、不能一键收藏，原因就在上面的思考里面了」。")

    add_h2(doc, "2.11 快捷操作与多入口")
    add_p(doc, "flomo 的入口策略是「在哪有灵感就在哪里记」，覆盖面很广：")
    add_bullet(doc, "微信服务号输入：最受好评的入口，像发给朋友一条消息一样记笔记，2 秒搞定。")
    add_bullet(doc, "API 输入：支持 iOS 快捷指令、Taio、轻芒、Alfred、Chrome 扩展、Telegram Bot、Automator 等。API 用一串随机字符做认证，lightory 吐槽「OAuth 是人类的敌人」。")
    add_bullet(doc, "iOS 长按 App 图标：弹出快速记录和搜索。")
    add_bullet(doc, "微信语音输入：支持语音转文字记录。")
    add_p(doc,
          "API 之所以能在 2020 年 11 月就上线（原计划 2021 Q2），是因为 lightory 想清楚两件事：不需要功能完备的 API，需求最旺的是输入 API；不需要严谨的 OAuth 认证，一串随机字符就够了。这个判断支撑了后来丰富的第三方工具生态。")

    add_h2(doc, "2.12 Widget 小组件")
    add_p(doc, "Widget 是 flomo 回顾体系的重要载体，覆盖了 iOS 的几个层级：")
    add_bullet(doc, "主屏小组件：每日回顾，可以按标签筛选（比如只回顾 #books 标签的读书笔记），每次解锁手机都能看到过去记录。")
    add_bullet(doc, "锁屏小组件：iOS 16 适配，锁屏就能看到回顾内容。")
    add_bullet(doc, "控制中心按钮：iOS 18 适配，包括快速记录、搜索、那年今日、每日回顾、找一找五个入口。")
    add_p(doc,
          "设计意图是「每次解锁手机都是一次回顾」。把回顾从主动行为变成被动行为，利用碎片时间让过往记录发挥价值。豆瓣上有用户分享，情绪低落时随机回顾到记录下的美好小事，能给情绪充电。")

    add_h2(doc, "2.13 Apple Watch 支持")
    add_p(doc,
          "调研中未找到 flomo 有原生 Apple Watch 应用的明确证据。从产品逻辑看，flomo 的核心交互是文字输入，Apple Watch 的小屏和输入限制不太适合直接记录。但通过 iOS 快捷指令，理论上可以从手表触发 API 输入。这一点和 Day One（有原生 Watch 应用，主要做拍照和语音）形成对比。")

    add_h2(doc, "2.14 提醒与回顾功能")
    add_p(doc, "回顾体系是 flomo 的核心差异化，分几个层次：")
    add_bullet(doc, "每日回顾：通过微信服务号或 Widget，按条件（标签、时间）下发历史记录。用户可以设置每晚固定时间推送某标签下的随机内容。")
    add_bullet(doc, "随机漫步：根据笔记内容做语义分析，随机展示可能相关的词汇或笔记，帮用户发现笔记间隐藏的联系。可玩性很高，能启发新洞见。")
    add_bullet(doc, "相关笔记：4.0 版本上线，根据任意一条笔记的内容，联想推荐另一条笔记。用户评价这是 flomo 的「完全体」，意义相当于 App Store 之于 iPhone。")
    add_bullet(doc, "找一找：iOS 18 控制中心入口之一，探索笔记关联。")
    add_p(doc,
          "这套回顾体系的理论基础是「必要难度理论」：输入时越困难，提取时越容易。flomo 通过被动回顾，让笔记在不同场景下被多次调用，符合记忆规律。少楠说，「大多数人的问题不在整理，而在于许多想法都没记下来」，回顾体系解决的是「记了之后怎么用」的问题。")

    add_h2(doc, "2.15 加密与隐私")
    add_p(doc,
          "技术上用 SSL 加密传输，数据存数据库。官方承诺「不窥探任何用户数据」「不贩卖隐私数据获得利润」，把这一点写进了经营理念。这和 flomo 的商业模式有关：不靠广告，所以不需要贩卖用户注意力。订阅制让用户和产品利益一致。")

    add_h2(doc, "2.16 多端同步细节")
    add_p(doc, "覆盖 iOS、Android、Web、PWA、微信服务号。同步永久免费，不限设备数。技术选型上有意思：首版用 Web + PWA，因为「Web 技术意味着更好的兼容性和更快的迭代速度」。等产品定型后才转原生 iOS/Android，因为「长期价值的权重会提高，原生技术能提供更好的体验」。这个演进路径对小团队有参考价值。")

    add_h2(doc, "2.17 统计与知识图谱")
    add_p(doc,
          "PRO 会员有多达八个维度的统计分析，包括贡献墙（参考 GitHub 和豆瓣的设计，量化 MEMO 数量）、标签树、标签矩阵等。这些统计的意图是「让用户即时看到自己积累的 MEMO 数量，获得记录的成就感」，属于「提高输入动机」的设计。")

    # ============ 三、设计与交互亮点 ============
    add_h1(doc, "三、设计与交互亮点")

    add_h2(doc, "3.1 视觉风格")
    add_p(doc,
          "极简、克制、留白多。官方设计哲学里写得很直白：「尽量少的颜色，尽量少的个性，尽量少的奇怪排版。但同时尽可能符合你的习惯」「尽量少的有 ego，尊重使用者，不妄图扭曲他的欲望，我们只做好一个安静的容器」。有原研哉和无印良品的影子。少楠在播客里专门聊过设计美学，提到了原研哉的影响。")

    add_h2(doc, "3.2 微交互")
    add_p(doc,
          "聊天式输入是核心微交互。打字、发送、出现新 MEMO，整个过程像发微信消息一样顺滑。这种交互降低了「创建文档」的心理门槛，让记录变成一种对话。手动输入 # 标签也是微交互的一部分，虽然被吐槽，但符合「输入时不离开键盘」的逻辑。")

    add_h2(doc, "3.3 字体选择")
    add_p(doc,
          "用系统字体，不提供字体选择。这是「尽量少的设计」的体现。在中文环境下，系统字体（苹方、微软雅黑等）已经足够清晰，强行换字体反而会增加视觉噪音。")

    add_h2(doc, "3.4 留白与排版")
    add_p(doc,
          "留白充足，每条 MEMO 之间有明确的视觉间隔。时间倒序排列，按天分组。整体节奏是「一条一条」的卡片流，而不是密集的文章列表。这种排版让每条记录都有呼吸感，也方便回顾时逐条阅读。")

    add_h2(doc, "3.5 动效")
    add_p(doc,
          "动效克制。发送 MEMO 时的反馈、列表更新的过渡都很轻。没有花哨的页面切换、没有视差滚动、没有弹性动画。符合「拒绝做完美的产品」的理念，把动效预算花在核心交互上。")

    add_h2(doc, "3.6 颜色系统")
    add_p(doc,
          "颜色极少。主体是黑白灰，标签有颜色区分但克制。官方说有 6 个颜色主题，但默认风格是接近无色的。这种处理让内容成为视觉焦点，UI 退到背景里。")

    # ============ 四、商业模式 ============
    add_h1(doc, "四、商业模式")

    add_h2(doc, "4.1 定价模式")
    add_p(doc, "免费 + PRO 订阅，没有一次性买断。具体价格：")
    add_bullet(doc, "PRO 年费：99 元/年")
    add_bullet(doc, "PRO 月费：12 元/月")
    add_bullet(doc, "教育优惠：49-59 元/年（无需验证，靠诚信）")
    add_bullet(doc, "免费版：500M 存储空间，基础功能可用")

    add_h2(doc, "4.2 为什么不卖永久会员")
    add_p(doc,
          "官方明确拒绝永久会员，理由有两层。第一，保留退订权利：「我们从不鼓动你购买多年会员，反而总是劝阻。就是希望你能够保留退订的权利。这是一种压力，但却是有益的压力，它会确保我们持续关注为你创造价值」。第二，没有信心做到的不敢承诺：「没有信心做到的，我们绝不敢承诺。上一个卖永久会员的是乐视。音容笑貌犹在，请问永久是多久？」")

    add_p(doc,
          "lightory 解释过为什么不能买断：flomo 是云服务，随着使用时长增加存储空间不断增长，如果买断将来会导致入不敷出，可以参考 iCloud 或各种云盘服务的成本结构。")

    add_h2(doc, "4.3 「再服务 7 年」承诺")
    add_p(doc,
          "flomo 会预留一笔服务保证金，覆盖未来 7 年的服务器成本和基本人工维护成本。如果 flomo 进入「维持状态」（不得不放弃继续投入），这笔钱才会动用。意思是「以后我们每天都向你承诺再服务 7 年，直到某一天没办法继续承诺时，承诺才终于开始兑现，你可以再继续使用 7 年」。少楠说 7 年是此刻能力范围内的极限承诺，希望未来能变成 20 年。")

    add_h2(doc, "4.4 PRO 功能与免费功能差异")
    add_p(doc, "PRO 专属功能主要包括：")
    add_bullet(doc, "随机漫步（语义分析回顾）")
    add_bullet(doc, "数据导出（后来向所有用户开放，但限 7 天一次）")
    add_bullet(doc, "双向链接查看")
    add_bullet(doc, "多维度统计（标签树、标签矩阵等八个维度）")
    add_bullet(doc, "更大的存储空间")
    add_p(doc, "免费版功能也能满足基础记录需求，不付费日常使用完全没问题。")

    # ============ 五、用户口碑与评价 ============
    add_h1(doc, "五、用户口碑与评价")

    add_h2(doc, "5.1 典型好评")
    add_p(doc, "从少数派、豆瓣、即刻、头条等平台的讨论里，好评集中在几点：")
    add_bullet(doc, "「打开就能写，三秒搞定」。极简无压是最高频的赞美。")
    add_bullet(doc, "微信输入改变了记录习惯。很多人说绑微信后，flomo 替代了文件传输助手，成为碎片想法的收容器。")
    add_bullet(doc, "「够简单、够清晰、易提取」。和《卡片笔记写作法》的方法论结合，解决了「读完就忘」的问题。")
    add_bullet(doc, "每日回顾带来情绪价值。豆瓣用户分享，情绪低落时随机回顾到美好小事，能充电。")
    add_bullet(doc, "「会思考的软件」。相关笔记和随机漫步让用户发现笔记间隐藏联系，有「和自己对话」的感觉。")

    add_h2(doc, "5.2 用户批评的痛点")
    add_p(doc, "差评和吐槽也很集中：")
    add_bullet(doc, "标签功能反人类。ZOL 问答上多人吐槽要手动加 # 符号，不能点一下加标签，学习成本高。")
    add_bullet(doc, "功能太少。ZOL 用户评价「最大的优点是简洁，但缺点是功能太少，高级需求满足不了」。")
    add_bullet(doc, "理念宣传和实际体验有落差。有人比喻「就像方便面包装上的图，实际没有那么理想」。")
    add_bullet(doc, "免费版 500M 空间太小，记久了不够用。")
    add_bullet(doc, "PRO 还要年费，不买断。豆瓣上有用户专门找 flomo 的免费替代品。")
    add_bullet(doc, "标签越打越多，容易重叠割裂。豆瓣用户记了 4486 条后反思，很多是 FOMO 心理下的复制粘贴，不是自己写的。")

    add_h2(doc, "5.3 社区讨论的亮点")
    add_p(doc,
          "flomo 在少数派和即刻有很强的社区氛围。少楠自己就是活跃的产品经理博主，flomo weekly 周刊持续输出笔记方法论，降低了用户的使用门槛。这种「产品 + 内容」的双轮驱动，让用户不只是用工具，还在学方法。《笔记的方法》一书的出版，进一步强化了 flomo 作为「卡片笔记方法代表产品」的认知。")

    # ============ 六、差异化优势总结 ============
    add_h1(doc, "六、差异化优势总结（为 LifeTrack 改进提供依据）")

    add_p(doc,
          "综合调研，flomo 最值得 LifeTrack 借鉴的设计点有以下几条。这些不是功能清单，而是设计原则。")

    add_h2(doc, "6.1 输入摩擦最小化")
    add_p(doc,
          "flomo 的全部设计都围绕「降低输入摩擦」展开。主界面就是输入框，像发消息一样记；微信入口让记录发生在最高频的 App 里；API 让第三方工具都能往 flomo 灌内容。LifeTrack 如果做日记，要解决的核心问题不是功能多，而是用户打开 App 的心理门槛。把创建日记的步骤压到最少，甚至像 flomo 那样打开就能写，是第一优先级。")

    add_h2(doc, "6.2 回顾体系从主动变被动")
    add_p(doc,
          "flomo 的每日回顾、随机漫步、相关笔记、那年今日，本质上都在解决一个问题：记了之后怎么用。通过 Widget、微信推送、控制中心，把回顾变成解锁手机时的被动行为，不需要用户主动去翻。LifeTrack 的日记如果只停留在「记」，价值会随时间衰减；加上被动回顾机制，让过去的日记在当下被重新看见，才能形成「记录—回顾—再记录」的闭环。")

    add_h2(doc, "6.3 长期主义的商业模式承诺")
    add_p(doc,
          "「再服务 7 年」承诺、拒绝永久会员、不融资、靠订阅和用户利益绑定。这套设计让用户敢把长期数据托付给产品。LifeTrack 作为日记应用，数据沉淀周期更长，更需要这种长期承诺机制。订阅制比买断制更诚实地反映了云服务的成本结构，也更能维持产品持续迭代。")

    add_h2(doc, "6.4 极简设计哲学的克制")
    add_p(doc,
          "「尽量少的设计、颜色、个性、ego」，flomo 把这条原则执行得很彻底，连修改昵称的功能都一直没做。LifeTrack 在做功能取舍时，可以问自己一个问题：这个功能是用户价值的核心，还是只是功能的堆砌？如果不是核心，宁可不做。克制比堆功能难，但长期看更有竞争力。")

    add_h2(doc, "6.5 标签优于文件夹")
    add_p(doc,
          "flome 弱化文件夹，用多级标签组织内容，一条笔记可以打多个标签。这背后的逻辑是「知识像植物一样有机生长，传统分类无法满足」。LifeTrack 的日记如果按日期组织是基础，但加上标签维度（情绪、主题、人物），能让日记之间产生横向联系。不过要注意 flomo 的教训：标签要手动加 # 的设计争议大，需要在灵活性和易用性之间找平衡。")

    add_h2(doc, "6.6 产品 + 内容的双轮驱动")
    add_p(doc,
          "flomo weekly 周刊、《笔记的方法》一书、少楠的持续内容输出，让 flomo 不只是工具，还是方法论的载体。LifeTrack 如果想建立用户黏性，除了打磨产品，还可以输出「如何写日记」「日记的心理价值」等内容，把产品和方法论绑定。用户认可方法，就会认可产品。")

    add_divider(doc)
    add_p(doc,
          "以上调研基于 flomo 官网、官方帮助文档（flomo 101）、少数派文章、澎湃新闻、豆瓣讨论、即刻动态、ZOL 问答、应用宝介绍等多渠道信息。报告作为 LifeTrack 改进的参考依据，不构成投资或使用建议。",
          size=9)

    out = r"D:\Coding\AISummary\LifeTrack\浮墨笔记flomo产品调研报告.docx"
    doc.save(out)
    print("saved:", out)


if __name__ == "__main__":
    build()
