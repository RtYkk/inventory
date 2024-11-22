package jlu.meng.inventory

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.trimmedLength
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import jlu.meng.inventory.ui.theme.AppTheme
import jlu.meng.inventory.ui.theme.Colors
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.journeyapps.barcodescanner.ScanContract
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import java.time.LocalDate
//noinspection UsingMaterialAndMaterial3Libraries
import androidx.compose.material.Icon
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.runtime.Composable
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.room.Room
import kotlinx.coroutines.GlobalScope


val TAG = "MainActivity";

class MainActivity : ComponentActivity() {
    private lateinit var partDao: PartDao
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var state = AppViewModel(database = (application as App).db)
        state.viewModelScope.launch {
            state.load();
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContent {
            AppTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Main(state)
                }
            }
        }
        // 初始化数据库和 DAO
        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "inventory-database"
        ).build()
        partDao = db.partDao()

        // 获取 num_in_stock 的总和
        GlobalScope.launch {
            val totalNumInStock = partDao.getTotalNumInStock()
            Log.d("MainActivity", "Total num_in_stock: $totalNumInStock")
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun Main(state: AppViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "parts") {
        composable("parts") {
            Log.i(TAG, "Navigate to parts list")
            PartsScreen(nav, state)
        }
        composable("scans") {
            Log.i(TAG, "Navigate to scans list")
            ScansScreen(nav, state)
        }
        composable(
            "edit-part?id={id}",
            arguments = listOf(navArgument("id") { defaultValue = 0 })
        ) {
            val partId = it.arguments?.getInt("id");
            Log.i(TAG, "Navigate to edit-part?id=${partId}")
            val savedPart = state.parts.find { it.id == partId };
            var part = if (savedPart != null) savedPart else Part(id = 0)
            PartEditorScreen(nav, state, part)
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScansScreen(nav: NavHostController, state: AppViewModel) {
    val snackbarState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scaffoldState = rememberBottomSheetScaffoldState()
    var selectedScan by remember { mutableStateOf(Scan(id = 0)) }
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        snackbarHost = { SnackbarHost(snackbarState) },
        topBar = {
            TopAppBar(
                modifier = Modifier.shadow(20.dp),
                title = { Text("扫描的条形码") },
                navigationIcon =
                {
                    IconButton(onClick = { nav.navigateUp() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        content = {
            Column(
                modifier = Modifier
                    .padding(it)
                    .background(MaterialTheme.colorScheme.background)
                    .fillMaxSize()
            ) {
                ScanList(state.scans, { scan ->
                    scope.launch {
                        selectedScan = scan;
                        scaffoldState.bottomSheetState.expand()
                    }
                })
            }
        },
        sheetPeekHeight = 0.dp,
        sheetShadowElevation = 20.dp,
        sheetContent = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            ) {
                Text("条形码内容", fontWeight = FontWeight.Bold)
                SelectionContainer {
                    Text(selectedScan.value, modifier = Modifier.padding(0.dp, 8.dp))
                }
                var part = selectedScan.part;
                if (part != null) {
                    Button(
                        onClick = {
                            scope.launch {
                                val savedPart = state.parts.find { it.mpn == part.mpn };
                                if (savedPart == null) {
                                    state.addPart(part)
                                    nav.navigate("edit-part?id=${part.id}")
                                } else {
                                    nav.navigate("edit-part?id=${savedPart.id}")
                                }
                            }
                        }
                    ) {
                        Text("打开零件")
                    }
                } else {
                    Text("未知条形码格式")
                    if (selectedScan.value.trimmedLength() > 0) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val part = Part(id = 0, mpn = selectedScan.value)
                                    state.addPart(part)
                                    nav.navigate("edit-part?id=${part.id}")
                                }
                            }
                        ) {
                            Text("作为新零件的 MPN 导入")
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun ScanList(scans: List<Scan>, onScanClicked: (scan: Scan) -> Unit) {
    LazyColumn(
        state = rememberLazyListState(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(scans) { scan ->
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth()
                    .clickable {
                        onScanClicked(scan)
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp)

            ) {
                var valid = scan.isValid();

                Icon(
                    modifier = Modifier
                        .width(32.dp)
                        .height(32.dp)
                        .align(Alignment.CenterVertically),
                    imageVector = if (valid) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = if (valid) "有效扫描" else "无效扫描",
                    tint = if (valid) Color.Green else Color.Red
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        scan.created.toString(),
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 10.sp
                    )
                    Text(scan.value, maxLines = 1)
                }

            }
        }
    }
}

// Search parts in memory. Can use " and " to separate multiple filters
// and
fun search(parts: List<Part>, query: String, ignoreCase: Boolean = true): List<Part> {
    if (query.trimmedLength() == 0) {
        return parts // No query
    }
    val splitQuery = query.lowercase().replace(" and ", ",").split(",")
    return parts.filter { part ->
        val desc = part.description.replace(
            "µ", "u"
        ).replace("Ω", "Ohm").replace("ꭥ", "Ohm")
        splitQuery.all {
            var q = it.trim()
            if (q.length == 0)
                true// Ignore empty strings
            else if (":" in q) {
                val (k, v) = q.split(":", limit = 2)
                if (k.trimmedLength() == 0 || v.trimmedLength() == 0) {
                    true // Ignore empty key or value
                } else when (k.trim().lowercase()) {
                    "mpn" -> part.mpn.contains(v.trim(), ignoreCase)
                    "mfg" -> part.manufacturer.contains(v.trim(), ignoreCase)
                    "sku" -> part.sku.contains(v.trim(), ignoreCase)
                    "desc" -> desc.contains(v.trim(), ignoreCase)
                    else -> false
                }
            } else {
                part.mpn.contains(q, ignoreCase)
                        || part.manufacturer.contains(q, ignoreCase)
                        || desc.contains(q, ignoreCase)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun PartsScreen(nav: NavHostController, state: AppViewModel) {
    val context = LocalContext.current;
    val snackbarState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var searchText by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    val filteredParts = search(state.parts, searchText)
    val cameraPermissionState = rememberPermissionState(
        android.Manifest.permission.CAMERA
    )
    val scanLauncher = rememberLauncherForActivityResult(
        contract = ScanContract(),
        onResult = { result ->
            Log.i(TAG, "scanned code: ${result.contents}")
            scope.launch {
                var scan = Scan(id = state.scans.size + 1, value = result.contents)
                state.addScan(scan);
                var part = scan.part;
                if (part != null && part.mpn.length > 0) {
                    val existingPart = state.parts.find { it.mpn == part.mpn };
                    if (existingPart == null) {
                        state.addPart(part);
                        snackbarState.showSnackbar(message = "扫描到新零件 ${part.mpn}")
                        nav.navigate("edit-part?id=${part.id}")
                    } else {
                        snackbarState.showSnackbar(message = "打开零件 ${part.mpn}")
                        nav.navigate("edit-part?id=${existingPart.id}")
                    }
                } else {
                    snackbarState.showSnackbar(message = "未知条形码格式")
                }
            }
        }
    )

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.sqlite3")
    ) { mediaPath ->
        Log.d("Export", "Media path is ${mediaPath}");
        if (mediaPath != null) {
            context.contentResolver.openOutputStream(mediaPath, "wt")?.use { stream ->
                if (state.exportDb(stream) > 0) {
                    Toast.makeText(context, "导出完成！", 3000).show()
                } else {
                    Toast.makeText(context, "导出失败！", 3000).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { mediaPath ->
        Log.d("Import", "Media path is ${mediaPath}");
        if (mediaPath != null) {
            scope.launch {
                context.contentResolver.openInputStream(mediaPath)?.use { stream ->
                    if (state.importDb(context, stream) > 0) {
                        state.reload()
                        Toast.makeText(context, "导入完成！", 3000).show()
                    } else {
                        Toast.makeText(context, "导入失败！", 3000).show()
                    }
                }
            }
        }
    }

    var currentDate by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))) }
    var currentTime by remember { mutableStateOf(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.shadow(20.dp),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF5EB4FF) // Light blue color
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("所有零件")
                        Text(
                            "${state.parts.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .padding(8.dp)
                                .align(Alignment.CenterVertically)
                        )
                    }
                },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多"
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("添加零件") },
                            onClick = { nav.navigate("edit-part") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "添加零件",
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("导出数据库") },
                            onClick = {
                                expanded = false
                                exportLauncher.launch("inventory.db")
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.FileUpload,
                                    contentDescription = "导出",
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("导入数据库") },
                            onClick = {
                                expanded = false
                                importLauncher.launch(arrayOf("application/x-sqlite3","application/vnd.sqlite3", "application/octet-stream"))
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.FileDownload,
                                    contentDescription = "导入",
                                )
                            }
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarState) },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = CircleShape,
                    onClick = {
                        nav.navigate("scans")
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.List,
                        contentDescription = "查看扫描记录"
                    )
                }
                FloatingActionButton(
                    containerColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    onClick = {
                        showSearch = !showSearch
                        if (!showSearch) {
                            searchText = ""
                        }
                    }) {
                    Icon(
                        imageVector = if (!showSearch) Icons.Filled.Search else Icons.Filled.SearchOff,
                        contentDescription = "搜索",
                    )
                }
                FloatingActionButton(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    shape = CircleShape,
                    onClick = {
                        if (!cameraPermissionState.status.isGranted) {
                            val textToShow = if (cameraPermissionState.status.shouldShowRationale) {
                                "此应用需要相机权限才能扫描条形码，请授予权限。"
                            } else {
                                "需要相机权限才能使用此功能，请在设置中授予权限。"
                            }
                            scope.launch {
                                var result = snackbarState.showSnackbar(
                                    message = textToShow,
                                    actionLabel = "确定",
                                )
                                when (result) {
                                    SnackbarResult.Dismissed -> {
                                    }
                                    SnackbarResult.ActionPerformed -> {
                                        cameraPermissionState.launchPermissionRequest()
                                    }
                                }
                            }
                        } else {
                            scanLauncher.launch(state.scanOptions)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.QrCodeScanner,
                        contentDescription = "扫描条形码"
                    )
                }
            }

        },
        content = {
            Column(
                modifier = Modifier
                    .padding(it)
                    .fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .padding(16.dp)
                        .wrapContentWidth()
                        .align(Alignment.CenterHorizontally)
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(16.dp),
                            spotColor = Color.Black.copy(alpha = 0.25f),
                            ambientColor = Color.Black.copy(alpha = 0.25f)
                        )
                        .background(Color(0xFF5EB4FF), RoundedCornerShape(16.dp))
                ) {
                    Column {
                        Text(
                            text = currentTime,
                            fontSize = 58.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp, 32.dp, 32.dp, 4.dp)
                                .align(Alignment.CenterHorizontally),
                            textAlign = TextAlign.Center,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = currentDate,
                            fontSize = 24.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp, 4.dp, 32.dp, 32.dp)
                                .align(Alignment.CenterHorizontally),
                            textAlign = TextAlign.Center,
                            letterSpacing = 2.sp
                        )
                    }
                }
                PartsList(parts = filteredParts) { part ->
                    Log.d(TAG, "Clicked part ${part}")
                    nav.navigate("edit-part?id=${part.id}")
                }
            }

        },
        bottomBar = {
            if (showSearch) {
                BottomAppBar(
                    modifier = Modifier.shadow(20.dp),
                    containerColor = MaterialTheme.colorScheme.background,
                    actions = {
                        OutlinedTextField(
                            label = { Text("搜索") },
                            placeholder = {
                                Text(
                                    "零件编号、制造商或描述。使用逗号或and分隔多个条件",
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .fillMaxSize()
                                )
                            },
                            modifier = Modifier
                                .padding(8.dp)
                                .fillMaxWidth()
                                .align(Alignment.CenterVertically),
                            trailingIcon = {
                                if (searchText.length > 0) {
                                    Icon(
                                        imageVector = Icons.Filled.Cancel,
                                        contentDescription = "清除",
                                        modifier = Modifier.clickable {
                                            searchText = ""
                                        }
                                    )
                                }
                            },
                            value = searchText,
                            onValueChange = {
                                searchText = it
                            }
                        )
                    }
                )
            }
        }
    )
}

@Composable
fun PartsList(parts: List<Part>, onPartClicked: (part: Part) -> Unit) {
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(parts, key = { p -> p.id } ) { part ->
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth()
                    .clickable {
                        onPartClicked(part)
                    }
            ) {
                Text(
                    "${parts.indexOf(part) + 1}.",
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .width(32.dp)
                        .align(Alignment.CenterVertically),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                if (part.pictureUrl.length > 0) {
                    val req =
                        ImageRequest.Builder(LocalContext.current).data(part.pictureUrl).diskCachePolicy(
                            CachePolicy.ENABLED).httpHeaders(
                            NetworkHeaders.Builder().add("User-Agent", userAgent).build()
                        );
                    AsyncImage(
                        model = req.build(),
                        contentDescription = "零件图片",
                        modifier = Modifier
                            .width(48.dp)
                            .height(48.dp)
                            .padding(8.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.ImageNotSupported,
                        contentDescription = "无图片",
                        modifier = Modifier
                            .width(48.dp)
                            .height(48.dp)
                            .padding(8.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(5f)
                ) {
                    Text(
                        part.mpn,
                        fontSize = 24.sp,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1
                    )
                    Text(
                        part.manufacturer,
                        fontSize = 12.sp,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1
                    )
                }
                Text(
                    "${part.num_in_stock}",
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(64.dp),
                    fontSize = 18.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmRemoveDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { onDismiss() }) {
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.background
            ),
            modifier = Modifier.padding(8.dp),
            elevation = CardDefaults.cardElevation(20.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = "确定要删除吗？",
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    TextButton(
                        onClick = { onDismiss() }
                    ) {
                        Text("取消")
                    }
                    TextButton(
                        onClick = {
                            onConfirm()
                        }
                    ) {
                        Text("确定", color = Colors.Danger)
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartEditorScreen(nav: NavHostController, state: AppViewModel, originalPart: Part) {
    Log.i(TAG, "Editing part ${originalPart}")
    val context = LocalContext.current;
    val scope = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }
    var partId by remember { mutableStateOf(originalPart.id) };
    var partDesc by remember { mutableStateOf(originalPart.description) };
    var partManufacturer by remember { mutableStateOf(originalPart.manufacturer) };
    var partLocation by remember { mutableStateOf(originalPart.location) };

    var partSku by remember { mutableStateOf(originalPart.sku) };
    var partMpn by remember { mutableStateOf(originalPart.mpn) };
    var partSupplier by remember { mutableStateOf(originalPart.supplier) };
    var partSupplierUrl by remember { mutableStateOf(originalPart.supplierUrl()) }
    var partNumOrdered by remember { mutableStateOf(originalPart.num_ordered) };
    var partNumInStock by remember { mutableStateOf(originalPart.num_in_stock) };
    var partUnitPrice by remember { mutableStateOf(originalPart.unit_price) };

    var partDatasheet by remember { mutableStateOf(originalPart.datasheetUrl) };
    var partImage by remember { mutableStateOf(originalPart.pictureUrl) };
    var partUpdated by remember { mutableStateOf(originalPart.updated) };
    var editMode by remember { mutableStateOf(false) }
    var editing = partId == 0 || editMode;

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarState) },
        topBar = {
            TopAppBar(
                modifier = Modifier.shadow(20.dp),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF5EB4FF) // Light blue color
                ),
                title = {
                    if (partId == 0) {
                        Text("添加零件")
                    } else {
                        SelectionContainer {
                            Text(partMpn, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon =
                {
                    IconButton(onClick = { nav.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    var showRemoveDialog by remember { mutableStateOf(false) };
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多"
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        if (partMpn.isNotBlank() || partSku.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("从供应商导入") },
                                onClick = {
                                    scope.launch {
                                        when (originalPart.importFromSupplier()) {
                                            ImportResult.Success -> {
                                                // Force update
                                                partImage = originalPart.pictureUrl
                                                partDatasheet = originalPart.datasheetUrl
                                                partDesc = originalPart.description
                                                partManufacturer = originalPart.manufacturer
                                                partUpdated = originalPart.updated

                                                // If import is pressed before save
                                                var msg = "导入成功！"
                                                if (partId == 0) {
                                                    if (state.addPart(originalPart)) {
                                                        partId = originalPart.id
                                                    } else {
                                                        msg = "该零件型号已存在！"
                                                    }
                                                } else {
                                                    state.savePart(originalPart);
                                                }
                                                snackbarState.showSnackbar(msg)
                                            }
                                            ImportResult.NoData -> {
                                                snackbarState.showSnackbar("未找到可导入的数据。")
                                            }
                                            ImportResult.MultipleResults -> {
                                                val r = snackbarState.showSnackbar(
                                                    "未找到匹配的零件。请试试添加 SKU",
                                                    actionLabel = "搜索供应商网站"
                                                )
                                                when (r) {
                                                    SnackbarResult.ActionPerformed -> {
                                                        try {
                                                            val browserIntent =
                                                                Intent(
                                                                    Intent.ACTION_VIEW,
                                                                    Uri.parse(originalPart.supplierUrl())
                                                                )
                                                            context.startActivity(browserIntent)
                                                        } catch (e: Exception) {
                                                            scope.launch {
                                                                snackbarState.showSnackbar("搜索网址无效")
                                                            }
                                                        }
                                                    }
                                                    SnackbarResult.Dismissed -> {}
                                                }
                                            }
                                            else -> {
                                                snackbarState.showSnackbar("导入失败。")
                                            }
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.SystemUpdateAlt,
                                        contentDescription = "导入",
                                    )
                                })
                        }
                        if (partId != 0) {
                            DropdownMenuItem(
                                text = { Text("删除", color = Colors.Danger) },
                                onClick = {
                                    showRemoveDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                    )
                                })
                        }
                    }
                    if (showRemoveDialog) {
                        ConfirmRemoveDialog(
                            onDismiss = { showRemoveDialog = false },
                            onConfirm = {
                                scope.launch {
                                    nav.navigateUp()
                                    state.removePart(originalPart)
                                }
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(
                    containerColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    onClick = { editMode = !editMode }
                ) {
                    Icon(
                        imageVector = if (!editMode) Icons.Filled.Edit else Icons.Filled.EditOff,
                        contentDescription = "编辑"
                    )
                }
                FloatingActionButton(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    shape = CircleShape,
                    onClick = {
                        scope.launch {
                            var msg = "保存成功！"
                            if (partId == 0) {
                                if (state.addPart(originalPart)) {
                                    partId = originalPart.id
                                    Log.d(TAG, "添加新零件 ${originalPart.id}")
                                } else {
                                    msg = "无法保存。该零件型号已存在！"
                                }

                            } else {
                                state.savePart(originalPart);
                            }
                            snackbarState.showSnackbar(msg)
                        }

                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = "保存"
                    )
                }
            }
        },
        content = {
            Column(
                modifier = Modifier
                    .padding(it)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                if (partImage.length > 0) {
                    AsyncImage(
                        model = partImage,
                        contentDescription = null,
                        modifier = Modifier
                            .height(200.dp)
                            .padding(16.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                }

                Row {
                    if (partDatasheet.length > 0) {
                        Button(
                            modifier = Modifier.padding(8.dp),
                            onClick = {
                                try {
                                    val browserIntent =
                                        Intent(Intent.ACTION_VIEW, Uri.parse(partDatasheet))
                                    context.startActivity(browserIntent)
                                } catch (e: Exception) {
                                    scope.launch {
                                        snackbarState.showSnackbar("数据手册链接无效")
                                    }
                                }
                            }
                        ) {
                            Text("数据手册")
                        }
                    }
                    if (partSupplierUrl.isNotBlank()) {
                        Button(
                            modifier = Modifier.padding(8.dp),
                            onClick = {
                                try {
                                    val browserIntent =
                                        Intent(Intent.ACTION_VIEW, Uri.parse(partSupplierUrl))
                                    context.startActivity(browserIntent)
                                } catch (e: Exception) {
                                    scope.launch {
                                        snackbarState.showSnackbar("供应商网址无效")
                                    }
                                }
                            }
                        ) {
                            Text("访问供应商网站")
                        }
                    }
                }

                if (editing) {
                    OutlinedTextField(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        singleLine = true,
                        value = partMpn,
                        onValueChange = { partMpn = it; originalPart.mpn = it },
                        label = { Text("零件型号") },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Badge,
                                contentDescription = "零件型号",
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    )
                    OutlinedTextField(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        value = partManufacturer,
                        onValueChange = { partManufacturer = it; originalPart.manufacturer = it },
                        singleLine = true,
                        label = { Text("制造商") },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Factory,
                                contentDescription = "制造商",
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    )

                    OutlinedTextField(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        value = partDesc,
                        onValueChange = { partDesc = it; originalPart.description = it },
                        singleLine = true,
                        label = { Text("描述") },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Description,
                                contentDescription = "描述",
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    )
                    OutlinedTextField(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        singleLine = true,
                        value = partSku,
                        onValueChange = { partSku = it; originalPart.sku = it },
                        label = { Text("供应商 SKU") },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Pin,
                                contentDescription = "供应商 SKU",
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    )
                    OutlinedTextField(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        singleLine = true,
                        value = partSupplier,
                        onValueChange = { partSupplier = it; originalPart.supplier = it },
                        label = { Text("供应商") },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Storefront,
                                contentDescription = "供应商",
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    )
                } else {
                    Text(
                        "最后更新于 ${partUpdated.toString()}",
                        fontWeight = FontWeight.Light,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp, 4.dp)
                    )
                    Text(
                        "零件型号",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp, 8.dp, 8.dp, 0.dp)
                    )
                    SelectionContainer {
                        Text(partMpn, modifier = Modifier.padding(8.dp))
                    }
                    Text(
                        "制造商",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp, 8.dp, 8.dp, 0.dp)
                    )
                    SelectionContainer {
                        Text(
                            if (partManufacturer.trimmedLength() > 0) partManufacturer else "N/A",
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    Text(
                        "描述",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp, 8.dp, 8.dp, 0.dp)
                    )
                    SelectionContainer {
                        Text(
                            if (partDesc.trimmedLength() > 0) partDesc else "N/A",
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    Text(
                        "单价",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp, 8.dp, 8.dp, 0.dp)
                    )
                    SelectionContainer {
                        Text(
                            "$${"%.5f".format(partUnitPrice)}",
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    Text(
                        "总价",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp, 8.dp, 8.dp, 0.dp)
                    )
                    SelectionContainer {
                        Text(
                            "$${"%.5f".format(partUnitPrice * partNumOrdered)}",
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    Text(
                        "供应商",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp, 8.dp, 8.dp, 0.dp)
                    )
                    SelectionContainer {
                        Text(
                            if (partSupplier.trimmedLength() > 0) partSupplier else "N/A",
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    Text(
                        "供应商 SKU",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp, 8.dp, 8.dp, 0.dp)
                    )
                    SelectionContainer {
                        Text(
                            if (partSku.trimmedLength() > 0) partSku else "N/A",
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                OutlinedTextField(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    value = partNumOrdered.toString(),
                    onValueChange = {
                        try {
                            val v = Integer.parseUnsignedInt(it);
                            partNumOrdered = v;
                            originalPart.num_ordered = v
                        } catch (e: Exception) {
                            Log.d(TAG, e.toString());
                            partNumOrdered = 0;
                            originalPart.num_ordered = 0
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    label = { Text("订购数量") }
                )
                OutlinedTextField(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    value = partNumInStock.toString(),
                    onValueChange = {
                        try {
                            val v = Integer.parseUnsignedInt(it);
                            partNumInStock = v;
                            originalPart.num_in_stock = v
                        } catch (e: Exception) {
                            Log.d(TAG, e.toString());
                            partNumInStock = 0;
                            originalPart.num_in_stock = 0
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    label = { Text("库存数量") }
                )

                OutlinedTextField(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    singleLine = true,
                    value = partLocation,
                    onValueChange = { partLocation = it; originalPart.location = it },
                    label = { Text("存放位置") },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = "存放位置",
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                )
                if (editing) {
                    OutlinedTextField(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        value = partUnitPrice.toString(),
                        onValueChange = {
                            try {
                                val v = it.toDouble();
                                partUnitPrice = v;
                                originalPart.unit_price = v
                            } catch (e: Exception) {
                                Log.d(TAG, e.toString());
                                partUnitPrice = 0.0
                                originalPart.unit_price = 0.0
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        label = { Text("单价") },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.AttachMoney,
                                contentDescription = "单价",
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    )

                    OutlinedTextField(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        value = partDatasheet,
                        onValueChange = { partDatasheet = it; originalPart.datasheetUrl = it },
                        singleLine = true,
                        label = { Text("数据手册链接") },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = "数据手册",
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { 
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://p.sda1.dev/"))
                                context.startActivity(browserIntent)
                            },
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate, 
                                contentDescription = "添加图片"
                            )
                        }
                        OutlinedTextField(
                            modifier = Modifier
                                .weight(1f),
                            value = partImage,
                            onValueChange = { partImage = it; originalPart.pictureUrl = it },
                            singleLine = true,
                            label = { Text("图片链接") }
                        )
                    }
                }
                Text(
                    "创建于 ${originalPart.created.toString()}",
                    fontWeight = FontWeight.Light,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp, 4.dp)
                )
            }
        }
    )
}

fun startActivityForResult(intent: Intent, requestCodeSelectImage: Int) {
    try {
        startActivityForResult(intent, requestCodeSelectImage)
    } catch (e: Exception) {
        Log.e(TAG, "Error starting activity for result: ${e.message}")
   }
}
//
//
//@Preview(showBackground = true)
//@Composable
//fun DefaultPreview() {
//    var state = AppViewModel(database = (application as App).db)
//    AppTheme {
//        Main(state)
//    }
//}