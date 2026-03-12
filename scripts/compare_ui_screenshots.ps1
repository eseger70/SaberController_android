param(
    [Parameter(Mandatory = $true)]
    [string]$Baseline,
    [Parameter(Mandatory = $true)]
    [string]$Candidate,
    [string]$DiffOutput,
    [double]$FailPercentThreshold = -1
)

$ErrorActionPreference = 'Stop'

Add-Type -ReferencedAssemblies 'System.Drawing' -TypeDefinition @"
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;

public sealed class ScreenshotCompareResult {
  public int Width { get; set; }
  public int Height { get; set; }
  public long DifferingPixels { get; set; }
  public double PercentDifferent { get; set; }
  public string DiffPath { get; set; }
}

public static class ScreenshotComparer {
  private static Bitmap ConvertToArgb(Bitmap source) {
    Bitmap converted = new Bitmap(source.Width, source.Height, PixelFormat.Format32bppArgb);
    using (Graphics graphics = Graphics.FromImage(converted)) {
      graphics.DrawImage(source, 0, 0, source.Width, source.Height);
    }
    return converted;
  }

  public static ScreenshotCompareResult Compare(string baselinePath, string candidatePath, string diffPath) {
    using (Bitmap baselineSource = new Bitmap(baselinePath))
    using (Bitmap candidateSource = new Bitmap(candidatePath))
    using (Bitmap baseline = ConvertToArgb(baselineSource))
    using (Bitmap candidate = ConvertToArgb(candidateSource)) {
      if (baseline.Width != candidate.Width || baseline.Height != candidate.Height) {
        throw new InvalidOperationException(
          string.Format(
            "Image size mismatch. Baseline={0}x{1}, Candidate={2}x{3}",
            baseline.Width,
            baseline.Height,
            candidate.Width,
            candidate.Height));
      }

      Rectangle rect = new Rectangle(0, 0, baseline.Width, baseline.Height);
      BitmapData baseData = baseline.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
      BitmapData candidateData = candidate.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);

      try {
        int bytes = Math.Abs(baseData.Stride) * baseline.Height;
        byte[] baseBytes = new byte[bytes];
        byte[] candidateBytes = new byte[bytes];
        Marshal.Copy(baseData.Scan0, baseBytes, 0, bytes);
        Marshal.Copy(candidateData.Scan0, candidateBytes, 0, bytes);

        Bitmap diffBitmap = new Bitmap(baseline.Width, baseline.Height, PixelFormat.Format32bppArgb);
        BitmapData diffData = diffBitmap.LockBits(rect, ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);

        try {
          byte[] diffBytes = new byte[bytes];
          long differingPixels = 0;

          for (int y = 0; y < baseline.Height; y++) {
            int rowOffset = y * baseData.Stride;
            for (int x = 0; x < baseline.Width; x++) {
              int index = rowOffset + (x * 4);
              bool different =
                baseBytes[index] != candidateBytes[index] ||
                baseBytes[index + 1] != candidateBytes[index + 1] ||
                baseBytes[index + 2] != candidateBytes[index + 2] ||
                baseBytes[index + 3] != candidateBytes[index + 3];

              if (different) {
                differingPixels++;
                diffBytes[index] = 0;
                diffBytes[index + 1] = 0;
                diffBytes[index + 2] = 255;
                diffBytes[index + 3] = 255;
              } else {
                byte shade = (byte)((candidateBytes[index] + candidateBytes[index + 1] + candidateBytes[index + 2]) / 3);
                diffBytes[index] = shade;
                diffBytes[index + 1] = shade;
                diffBytes[index + 2] = shade;
                diffBytes[index + 3] = 48;
              }
            }
          }

          Marshal.Copy(diffBytes, 0, diffData.Scan0, bytes);
          if (!string.IsNullOrWhiteSpace(diffPath)) {
            string directory = Path.GetDirectoryName(diffPath);
            if (!string.IsNullOrWhiteSpace(directory)) {
              Directory.CreateDirectory(directory);
            }
            diffBitmap.Save(diffPath, ImageFormat.Png);
          }

          return new ScreenshotCompareResult {
            Width = baseline.Width,
            Height = baseline.Height,
            DifferingPixels = differingPixels,
            PercentDifferent = ((double) differingPixels / (baseline.Width * baseline.Height)) * 100.0,
            DiffPath = diffPath ?? string.Empty
          };
        } finally {
          diffBitmap.UnlockBits(diffData);
          diffBitmap.Dispose();
        }
      } finally {
        baseline.UnlockBits(baseData);
        candidate.UnlockBits(candidateData);
      }
    }
  }
}
"@

$baselinePath = (Resolve-Path $Baseline).Path
$candidatePath = (Resolve-Path $Candidate).Path
if (-not $DiffOutput) {
    $candidateDir = Split-Path -Parent $candidatePath
    $candidateStem = [IO.Path]::GetFileNameWithoutExtension($candidatePath)
    $DiffOutput = Join-Path $candidateDir ($candidateStem + '_diff.png')
}

$result = [ScreenshotComparer]::Compare($baselinePath, $candidatePath, $DiffOutput)

$summary = [pscustomobject]@{
    Baseline         = $baselinePath
    Candidate        = $candidatePath
    Width            = $result.Width
    Height           = $result.Height
    DifferingPixels  = $result.DifferingPixels
    PercentDifferent = [Math]::Round($result.PercentDifferent, 4)
    DiffImage        = $result.DiffPath
}

$summary | Format-List

if ($FailPercentThreshold -ge 0 -and $result.PercentDifferent -gt $FailPercentThreshold) {
    throw "Screenshot difference $($result.PercentDifferent)% exceeded threshold $FailPercentThreshold%."
}
