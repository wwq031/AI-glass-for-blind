# Windows PowerShell 5.1。仅在本机读取测试图，不调用在线 OCR。
param([Parameter(Mandatory)][string]$ImagePath)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Storage.StorageFile,Windows.Storage,ContentType=WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder,Windows.Graphics.Imaging,ContentType=WindowsRuntime]
$null = [Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime]
$null = [Windows.Globalization.Language,Windows.Globalization,ContentType=WindowsRuntime]
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('zh-Hans-CN'))
if (!$engine) { throw '请先安装 Windows 简体中文 OCR 语言组件。' }
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1

function Wait-Result($operation, $type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($operation))
    $task.Wait()
    $task.Result
}

function Read-Menu($path) {
    $file = Wait-Result ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
    $stream = Wait-Result ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    try {
        $decoder = Wait-Result ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Wait-Result ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        try {
            $result = Wait-Result ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
            (($result.Lines | ForEach-Object Text) -join '|') -replace '\s', ''
        } finally { $bitmap.Dispose() }
    } finally { $stream.Dispose() }
}

function Copy-Region($source, $rectangle, $width, $height) {
    $bitmap = [Drawing.Bitmap]::new($width, $height)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.InterpolationMode = 'HighQualityBicubic'
        $graphics.DrawImage($source, [Drawing.Rectangle]::new(0, 0, $width, $height), $rectangle, [Drawing.GraphicsUnit]::Pixel)
        $path = [IO.Path]::GetTempFileName()
        $tempFiles.Add($path)
        $bitmap.Save($path, [Drawing.Imaging.ImageFormat]::Png)
        $path
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}

$names = '浓缩咖啡,美式咖啡,拿铁,卡布奇诺,焦糖玛奇朵,法芙娜热巧克力,宇治抹茶鲜奶,伯爵奶茶,莓果花果茶,白桃乌龙奶盖,海盐可可奶盖,芋泥麻薯鲜奶,红枣桂圆枸杞茶'.Split(',')
$tempFiles = [Collections.Generic.List[string]]::new()
$path = (Resolve-Path -LiteralPath $ImagePath).ProviderPath
$original = [Drawing.Image]::FromFile($path)
try {
    if ($original.Width -ne 1126 -or $original.Height -ne 1257) { throw '本实验使用交接文档中指定的双栏咖啡菜单原图与标注。' }
    $full = [Drawing.Rectangle]::new(0, 0, $original.Width, $original.Height)
    $smallPath = Copy-Region $original $full 430 480
    $small = [Drawing.Image]::FromFile($smallPath)
    try {
        $largePath = Copy-Region $small ([Drawing.Rectangle]::new(0, 0, 430, 480)) $original.Width $original.Height
    } finally { $small.Dispose() }
    # 三块的位置为人工标注，用来排除插画与另一栏的影响。
    $blocks = foreach ($r in @([Drawing.Rectangle]::new(0,0,535,680), [Drawing.Rectangle]::new(0,690,542,554), [Drawing.Rectangle]::new(567,688,554,555))) {
        Read-Menu (Copy-Region $original $r $r.Width $r.Height)
    }
    $cases = [ordered]@{ '原图' = Read-Menu $path; '缩小' = Read-Menu $smallPath; '缩小后放大' = Read-Menu $largePath; '原图人工分区' = $blocks -join '|' }
    foreach ($case in $cases.GetEnumerator()) {
        $missed = @($names | Where-Object { !$case.Value.Contains($_) })
        [pscustomobject]@{ '输入' = $case.Key; '完整命中' = "$($names.Count - $missed.Count)/$($names.Count)"; '未完整命中' = $missed -join '、' }
    }
} finally {
    $original.Dispose()
    foreach ($file in $tempFiles) { Remove-Item -LiteralPath $file }
}
