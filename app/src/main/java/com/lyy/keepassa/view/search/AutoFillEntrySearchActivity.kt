/*
 * Copyright (C) 2020 AriaLyy(https://github.com/AriaLyy/KeepassA)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, you can obtain one at http://mozilla.org/MPL/2.0/.
 */


package com.lyy.keepassa.view.search

import android.annotation.TargetApi
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.View
import android.view.View.OnClickListener
import androidx.appcompat.widget.SearchView.OnQueryTextListener
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.arialyy.frame.util.adapter.RvItemClickSupport
import com.keepassdroid.database.PwEntry
import com.keepassdroid.database.PwEntryV4
import com.lyy.keepassa.R
import com.lyy.keepassa.base.BaseActivity
import com.lyy.keepassa.base.BaseApp
import com.lyy.keepassa.databinding.ActivityAutoFillEntrySearchBinding
import com.lyy.keepassa.entity.SimpleItemEntity
import com.lyy.keepassa.event.CreateOrUpdateEntryEvent
import com.lyy.keepassa.util.EventBusHelper
import com.lyy.keepassa.util.HitUtil
import com.lyy.keepassa.util.KeepassAUtil
import com.lyy.keepassa.util.cloud.DbSynUtil
import com.lyy.keepassa.view.create.CreateEntryActivity
import com.lyy.keepassa.view.dialog.LoadingDialog
import com.lyy.keepassa.view.dialog.MsgDialog
import com.lyy.keepassa.view.launcher.LauncherActivity
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode.MAIN

/**
 * 自动填充条目找不到时的选择页面
 */
class AutoFillEntrySearchActivity : BaseActivity<ActivityAutoFillEntrySearchBinding>() {

  private lateinit var module: SearchModule
  private lateinit var adapter: SearchAdapter
  private val listData: ArrayList<SimpleItemEntity> = ArrayList()
  private lateinit var loadingDialog: LoadingDialog
  private var curEntry: PwEntry? = null

  companion object {
    /**
     * 是否保存关联
     */
    const val EXTRA_IS_SAVE_RELEVANCE = "EXTRA_IS_SAVE_RELEVANCE"

    /**
     * 条目id
     */
    const val EXTRA_ENTRY_ID = "EXTRA_ENTRY_ID"
  }

  /**
   * 是否由自动填充服务启动
   */
  private var isFromFill: Boolean = false

  /**
   * 第三方应用包名
   */
  private var apkPkgName: String? = null

  override fun setLayoutId(): Int {
    return R.layout.activity_auto_fill_entry_search
  }

  override fun initData(savedInstanceState: Bundle?) {
    super.initData(savedInstanceState)
    if (BaseApp.KDB.pm == null) {
      BaseApp.isLocked = true
      finishAfterTransition()
      HitUtil.toaskShort(getString(R.string.notify_db_locked))
      return
    }
    EventBusHelper.reg(this)
    module = ViewModelProvider(this).get(SearchModule::class.java)
    apkPkgName = intent.getStringExtra(
        LauncherActivity.KEY_PKG_NAME
    )
    isFromFill = intent.getBooleanExtra(LauncherActivity.KEY_IS_AUTH_FORM_FILL, false)
    binding.search.requestFocusFromTouch()
    binding.search.setIconifiedByDefault(true)
    binding.search.isIconified = false
    binding.search.setOnQueryTextListener(object : OnQueryTextListener {
      /**
       * 当点击搜索按钮时触发该方法
       */
      override fun onQueryTextSubmit(query: String?): Boolean {
        if (query != null) {
          searchData(query)
        }

        return true
      }

      /**
       * 当搜索内容改变时触发该方法
       */
      override fun onQueryTextChange(newText: String?): Boolean {
        if (newText != null) {
          searchData(newText)
        }
        return true
      }

    })

    binding.exFab.setOnClickListener {
      startActivity(
          Intent(this, CreateEntryActivity::class.java).apply {
            putExtra(CreateEntryActivity.KEY_TYPE, CreateEntryActivity.TYPE_NEW_ENTRY)
          },
          ActivityOptions.makeSceneTransitionAnimation(this)
              .toBundle()
      )
    }

    initList()
  }

  /**
   * 搜索数据
   */
  private fun searchData(query: String) {
    if (query.isEmpty()) {
      listData.clear()
      adapter.notifyDataSetChanged()
      binding.noEntryLayout.visibility = View.VISIBLE
      return
    }
    module.searchEntry(query)
        .observe(this, Observer { list ->
          if (list != null) {
            binding.noEntryLayout.visibility = View.GONE
            listData.clear()
            listData.addAll(list)
            adapter.notifyDataSetChanged()
          } else {
            listData.clear()
            adapter.notifyDataSetChanged()
            binding.noEntryLayout.visibility = View.VISIBLE
          }
        })

  }

  /**
   * 初始化列表
   */
  private fun initList() {
    adapter =
      SearchAdapter(
          this, listData, OnClickListener { v ->
        val position = v.tag as Int
        val item = listData[position]
        module.delHistoryRecord(item.title)
            .observe(this, Observer { b ->
              listData.remove(item)
              adapter.notifyDataSetChanged()
            })

      })
    binding.list.layoutManager = LinearLayoutManager(this)
    binding.list.adapter = adapter
    RvItemClickSupport.addTo(binding.list)
        .setOnItemClickListener { _, position, _ ->
          val item = listData[position]
          val entry = item.obj as PwEntry
          val msg = Html.fromHtml(getString(R.string.hint_save_auto_fill, apkPkgName, entry.title))
          val msgDialog = MsgDialog.generate {
            msgTitle = this@AutoFillEntrySearchActivity.getString(R.string.hint)
            msgContent = msg
            build()
          }
          msgDialog.setOnBtClickListener(object : MsgDialog.OnBtClickListener {
            override fun onBtClick(
              type: Int,
              view: View
            ) {
              if (type == MsgDialog.TYPE_ENTER && entry is PwEntryV4) {
                // 保存记录
                relevanceEntry(entry)
              } else {
                callbackAutoFillService(false, entry)
              }
            }
          })
          msgDialog.show()
        }
  }

  /**
   * 关联记录
   */
  private fun relevanceEntry(pwEntry: PwEntryV4) {
    curEntry = pwEntry
    loadingDialog = LoadingDialog(this)
    loadingDialog.show()

    module.relevanceEntry(pwEntry, apkPkgName!!)
        .observe(this, Observer { code ->
          loadingDialog.dismiss()
          if (code == DbSynUtil.STATE_SUCCEED) {
            HitUtil.toaskShort("${getString(R.string.relevance_db)}${getString(R.string.success)}")
          } else {
            HitUtil.toaskShort("${getString(R.string.relevance_db)}${getString(R.string.fail)}")
          }
        })

  }

  /**
   * 填充数据到自动填充服务
   * @param isNotSaveRelevance 是否保存关联
   */
  @TargetApi(Build.VERSION_CODES.O)
  private fun callbackAutoFillService(
    isNotSaveRelevance: Boolean,
    pwEntry: PwEntry
  ) {
    if (!isFromFill) {
      val data = Intent().apply {
        putExtra(
            EXTRA_IS_SAVE_RELEVANCE, isNotSaveRelevance
        )
        putExtra(
            EXTRA_ENTRY_ID, pwEntry.uuid
        )
      }
      setResult(Activity.RESULT_OK, data)
      finishAfterTransition()
    } else {
      if (isNotSaveRelevance) {
        setResult(Activity.RESULT_OK, KeepassAUtil.getFillResponse(this, intent, apkPkgName!!))
      } else {
        setResult(
            Activity.RESULT_OK,
            KeepassAUtil.getFillResponse(this, intent, pwEntry, apkPkgName!!)
        )
      }
      finish()
    }
  }

  override fun onBackPressed() {
    super.onBackPressed()
    setResult(Activity.RESULT_CANCELED)
  }

  @Subscribe(threadMode = MAIN)
  fun onCreateEntry(event: CreateOrUpdateEntryEvent) {
    if (!event.isUpdate) {
      listData.clear()
      listData.add(KeepassAUtil.convertPwEntry2Item(event.entry))
      adapter.notifyDataSetChanged()
      binding.noEntryLayout.visibility = View.GONE
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    EventBusHelper.unReg(this)
  }

}