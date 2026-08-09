package com.xvd.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val modules = listOf(
            ModuleItem(
                title = "X 视频下载",
                subtitle = "监听剪贴板或粘贴链接，下载 X / 推特视频",
                iconRes = R.drawable.ic_module_video,
                target = MainActivity::class.java
            ),
            ModuleItem(
                title = "种子资源下载",
                subtitle = "粘贴磁力链接或选择 .torrent 文件，支持边下边播",
                iconRes = R.drawable.ic_module_torrent,
                target = TorrentActivity::class.java
            )
        )

        val recycler = findViewById<RecyclerView>(R.id.rvModules)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = ModuleAdapter(modules) { item ->
            startActivity(Intent(this, item.target))
        }
    }
}
