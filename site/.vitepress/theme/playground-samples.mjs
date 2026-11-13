// SPDX-License-Identifier: Apache-2.0
const lines = value => value.join('\n')
const data = value => JSON.stringify(value, null, 2)

const report = chinese => lines([
  '<document version="1" title="${report.title}" author="Export4j">',
  '  <page size="A4" margin="18mm"/>',
  '  <p size="10" space-after="18"><span role="bold" color="#176B4B">${report.company}</span> · ${report.period}</p>',
  '  <heading level="1" size="27" line-height="36" space-after="12">${report.title}</heading>',
  '  <p size="11" line-height="18" space-after="22">${report.summary}</p>',
  '  <heading level="2" size="15" line-height="24">' + (chinese ? '本期进展' : 'This period') + '</heading>',
  '  <each items="milestones" as="milestone">',
  '    <p size="11" line-height="18" space-after="10"><span role="bold">${milestone.name}</span> — ${milestone.result}</p>',
  '  </each>',
  '  <if test="showNext">',
  '    <then>',
  '      <heading level="2" size="15" line-height="24" space-before="16">' + (chinese ? '接下来的工作' : 'Up next') + '</heading>',
  '      <p size="11" line-height="18">${report.next}</p>',
  '    </then>',
  '    <else><p>' + (chinese ? '本期工作已完成。' : 'The current scope is complete.') + '</p></else>',
  '  </if>',
  '</document>'
])

const quotation = chinese => lines([
  '<document version="1" title="${quote.title}" author="Export4j">',
  '  <page size="A4" margin="18mm"/>',
  '  <p size="10" space-after="24"><span role="bold" color="#176B4B">${quote.supplier}</span></p>',
  '  <heading level="1" size="28" line-height="38">${quote.title}</heading>',
  '  <p space-after="20">${quote.reference} · ${quote.date}</p>',
  '  <p size="11" line-height="18" space-after="20">' + (chinese ? '客户：' : 'Prepared for: ') + '${quote.customer}</p>',
  '  <rows items="items" as="item" padding-top="9" padding-bottom="9" padding-left="8" padding-right="8">',
  '    <columns><column weight="3"/><column weight="1"/><column weight="1"/></columns>',
  '    <header><cell>' + (chinese ? '项目' : 'Description') + '</cell><cell align="CENTER">' + (chinese ? '数量' : 'Quantity') + '</cell><cell align="RIGHT">' + (chinese ? '金额' : 'Amount') + '</cell></header>',
  '    <row><cell>${item.name}</cell><cell align="CENTER">${item.quantity}</cell><cell align="RIGHT">${item.amount}</cell></row>',
  '  </rows>',
  '  <p size="16" line-height="26" align="RIGHT" space-before="16"><span role="bold">' + (chinese ? '合计  ' : 'Total  ') + '${quote.total}</span></p>',
  '  <p size="10" line-height="16" space-before="24">${quote.terms}</p>',
  '</document>'
])

const letter = chinese => lines([
  '<document version="1" title="${letter.subject}" author="${letter.sender}">',
  '  <page size="LETTER" margin="54" margin-top="60"/>',
  '  <p size="10" space-after="28"><span role="bold" color="#176B4B">${letter.company}</span></p>',
  '  <p size="10" align="RIGHT" space-after="28">${letter.date}</p>',
  '  <heading level="1" size="24" line-height="34" space-after="26">${letter.subject}</heading>',
  '  <p space-after="18">${letter.salutation}</p>',
  '  <p size="11" line-height="20" space-after="18">${letter.introduction}</p>',
  '  <p size="11" line-height="20" space-after="18">' + (chinese ? '我们建议从' : 'We suggest starting with ') + '<span role="bold" color="#176B4B">${letter.focus}</span>' + (chinese ? '开始，随后共同确定下一阶段的安排。' : ', then agreeing on the next phase together.') + '</p>',
  '  <p size="11" line-height="20" space-after="30">${letter.closing}</p>',
  '  <p size="11" line-height="18"><span role="bold">${letter.sender}</span></p>',
  '  <p size="10" line-height="16">${letter.position}</p>',
  '</document>'
])

export const PLAYGROUND_SAMPLES = [
  {
    id: 'report',
    name: { en: 'Project report', zh: '项目报告' },
    xml: { en: report(false), zh: report(true) },
    data: {
      en: data({
        report: { company: 'Northstar Studio', period: 'November 2026', title: 'A clear view of progress.', summary: 'A short project update, built from reusable structure and the data below. All names and details in this example are fictional.', next: 'Review the first release with the team and turn feedback into the next set of improvements.' },
        milestones: [{ name: 'Research', result: 'Completed six interviews and identified the core workflow.' }, { name: 'Prototype', result: 'Tested the first version with the project team.' }, { name: 'Delivery', result: 'Prepared the documentation and a small release checklist.' }],
        showNext: true
      }),
      zh: data({
        report: { company: '北辰设计工作室', period: '2026 年 11 月', title: '让每一步进展都清晰可见。', summary: '这是一份由模板结构和下方数据共同生成的项目简报。示例中的名称与业务内容均为虚构。', next: '和团队共同评审首个版本，并将反馈整理为下一阶段的改进计划。' },
        milestones: [{ name: '需求调研', result: '完成六次访谈，明确了主要工作流程。' }, { name: '原型验证', result: '与项目团队一起试用了第一个版本。' }, { name: '成果交付', result: '整理了使用文档与简洁的发布清单。' }],
        showNext: true
      })
    }
  },
  {
    id: 'quotation',
    name: { en: 'Quotation', zh: '商务报价' },
    xml: { en: quotation(false), zh: quotation(true) },
    data: {
      en: data({ quote: { supplier: 'Northstar Studio', title: 'A proposal for what comes next.', reference: 'Q-2026-018', date: '12 November 2026', customer: 'Willow & Co.', total: '$4,800', terms: 'Prices are illustrative. This fictional quotation is valid for 30 days. Amounts are formatted before binding; the template does not calculate totals.' }, items: [{ name: 'Discovery workshop', quantity: 1, amount: '$800' }, { name: 'Interface design', quantity: 1, amount: '$2,400' }, { name: 'Documentation & handover', quantity: 1, amount: '$1,600' }] }),
      zh: data({ quote: { supplier: '北辰设计工作室', title: '为下一步工作，准备好清晰的方案。', reference: 'Q-2026-018', date: '2026 年 11 月 12 日', customer: '青禾创意', total: '¥24,000', terms: '本报价为虚构示例，有效期 30 天。金额已在数据中完成格式化，模板不会自行计算合计。' }, items: [{ name: '需求调研工作坊', quantity: 1, amount: '¥4,000' }, { name: '界面设计', quantity: 1, amount: '¥12,000' }, { name: '文档整理与交付', quantity: 1, amount: '¥8,000' }] })
    }
  },
  {
    id: 'letter',
    name: { en: 'Business letter', zh: '商务信函' },
    xml: { en: letter(false), zh: letter(true) },
    data: {
      en: data({ letter: { company: 'Northstar Studio', date: '12 November 2026', subject: 'A thoughtful start.', salutation: 'Dear Alex,', introduction: 'Thank you for sharing your plans with us. We enjoyed learning about your team and the work you are bringing into the world.', focus: 'a small, focused discovery session', closing: 'We look forward to working together. Please share a suitable time, and we will prepare a short agenda.', sender: 'Morgan Lee', position: 'Project lead · Northstar Studio' } }),
      zh: data({ letter: { company: '北辰设计工作室', date: '2026 年 11 月 12 日', subject: '从一次认真沟通开始。', salutation: '青禾团队：', introduction: '感谢你们与我们分享新的项目计划。我们很高兴了解团队正在推进的工作，也期待一起把想法变成清晰、可执行的方案。', focus: '一场聚焦目标的小型需求讨论', closing: '期待与你们合作。请告知合适的时间，我们会提前准备一份简洁的议程。', sender: '林遥', position: '项目负责人 · 北辰设计工作室' } })
    }
  }
]
