package com.example.tv.paygate

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Fragment 内发起协程的统一入口：绑定 viewLifecycle，
 * 页面销毁即取消，避免确认页退出后轮询请求继续跑、回调已销毁的视图。
 */
fun Fragment.launchUi(block: suspend CoroutineScope.() -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch(block = block)
}
