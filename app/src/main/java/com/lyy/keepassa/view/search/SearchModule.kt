/*
 * Copyright (C) 2020 AriaLyy(https://github.com/AriaLyy/KeepassA)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, you can obtain one at http://mozilla.org/MPL/2.0/.
 */


package com.lyy.keepassa.view.search

import androidx.lifecycle.liveData
import com.keepassdroid.database.PwEntry
import com.keepassdroid.database.PwEntryV4
import com.keepassdroid.database.SearchParametersV4
import com.keepassdroid.database.security.ProtectedString
import com.lyy.keepassa.base.BaseApp
import com.lyy.keepassa.base.BaseModule
import com.lyy.keepassa.entity.SearchRecord
import com.lyy.keepassa.entity.SimpleItemEntity
import com.lyy.keepassa.util.KdbUtil
import com.lyy.keepassa.util.KeepassAUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchModule : BaseModule() {

  /**
   * 将条目和包名进行关联
   */
  fun relevanceEntry(
    pwEntry: PwEntryV4,
    apkPkgName: String
  ) = liveData {
    val code = withContext(Dispatchers.IO) {
      // 关联数据
      var nextId = 1
      for (i in 1 until 100) {
        if (pwEntry.strings["KP2A_URL_$i"] != null) {
          continue
        }
        nextId = i
        break
      }
      pwEntry.strings["KP2A_URL_$nextId"] = ProtectedString(false, "androidapp://$apkPkgName")
      return@withContext KdbUtil.saveDb()
    }
    emit(code)
  }

  /**
   * 搜索项目
   */
  fun searchEntry(query: String) = liveData {
    val sp = SearchParametersV4()
    val listStorage = ArrayList<PwEntry>()
    val data = ArrayList<SimpleItemEntity>()
    sp.searchString = query
    BaseApp.KDB.pm.rootGroup.searchEntries(sp, listStorage)
    if (listStorage.isEmpty()) {
      emit(null)
      return@liveData
    }

    for (entry in listStorage) {
      val item = KeepassAUtil.convertPwEntry2Item(entry)
      item.id = 2
      data.add(item)
    }
    emit(data)
  }

  /**
   * 获取搜索记录
   */
  fun getSearchRecord() = liveData {
    val records = withContext(Dispatchers.IO) {
      val dao = BaseApp.appDatabase.searchRecordDao()
      val temp = dao.getSearchRecord()
      val data = ArrayList<SimpleItemEntity>()
      for (record in temp) {
        data.add(convertRecord2Item(record))
      }
      data
    }
    emit(records)
  }

  /**
   * 转换记录为列表实体
   */
  private fun convertRecord2Item(record: SearchRecord): SimpleItemEntity {
    val item = SimpleItemEntity()
    item.time = record.time
    item.title = record.title
    item.id = 1
    return item
  }

  /**
   * 保存搜索数据
   * @param title 搜索的数据
   */
  fun saveSearchRecord(title: String) {
    GlobalScope.launch(Dispatchers.IO) {
      val dao = BaseApp.appDatabase.searchRecordDao()
      var record = dao.getRecord(title)
      if (record != null) {
        record.time = System.currentTimeMillis()
        dao.updateRecord(record)
      } else {
        record = SearchRecord()
        record.title = title
        record.time = System.currentTimeMillis()
        dao.saveRecord(record)
      }
    }
  }

  /**
   * 删除搜索历史
   */
  fun delHistoryRecord(title: String) = liveData {
    val b = withContext(Dispatchers.IO) {
      val dao = BaseApp.appDatabase.searchRecordDao()
      val record = dao.getRecord(title)
      if (record != null) {
        dao.delRecord(record)
      }
      true
    }
    emit(b)
  }
}