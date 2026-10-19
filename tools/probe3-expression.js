(async () => {
  window.zrcBridge.onEvents(JSON.stringify([
    { t: 'status', id: 'test-e2e-1', name: '通知链路测试', from: '运行中', to: '已完成' },
    { t: 'status', id: 'test-e2e-2', name: '出错场景测试', from: '运行中', to: '错误' },
  ]));
  return 'fired';
})()
