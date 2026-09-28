import re

from playwright.sync_api import expect


def run(page, parameters):
    page.goto(parameters["baseUrl"])
    expect(page.get_by_role("button", name=re.compile("测试任务"))).to_be_visible()
    page.get_by_role("button", name=re.compile("被测项目")).click()
    expect(page.get_by_role("heading", name=re.compile("Projects"))).to_be_visible()
    expect(page.get_by_role("button", name="新建", exact=True)).to_be_visible()
    page.get_by_role("button", name=re.compile("环境资源")).click()
    expect(page.get_by_role("heading", name=re.compile("Environments", re.IGNORECASE))).to_be_visible()
    page.get_by_role("button", name=re.compile("运行与报告")).click()
    expect(page.get_by_role("heading", name=re.compile("执行资源与队列"))).to_be_visible()
    page.get_by_role("button", name=re.compile("测试任务")).click()
    expect(page.get_by_role("heading", name=re.compile("Test Jobs", re.IGNORECASE))).to_be_visible()
    return "四个主导航、项目、任务、资源与报告页面均可通过 UI 到达"
