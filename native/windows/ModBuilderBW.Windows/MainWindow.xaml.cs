using System;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using ModBuilderBW.Windows.Models;
using ModBuilderBW.Windows.Services;

namespace ModBuilderBW.Windows;

public partial class MainWindow : Window
{
    private BuildRequestViewModel ViewModel => (BuildRequestViewModel)DataContext;

    public MainWindow()
    {
        InitializeComponent();
        ApplyWindowIcon();
        DataContext = new BuildRequestViewModel();
    }

    private void ApplyWindowIcon()
    {
        try
        {
            var iconPath = Path.Combine(AppContext.BaseDirectory, "app_icon.ico");
            if (!File.Exists(iconPath))
            {
                return;
            }

            Icon = BitmapFrame.Create(new Uri(iconPath, UriKind.Absolute));
        }
        catch
        {
            // Ignore icon load failures and keep the default window icon.
        }
    }

    private void AddFiles_OnClick(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Multiselect = true,
            CheckFileExists = true,
            Title = "Choose mod files"
        };
        if (dialog.ShowDialog(this) == true)
        {
            ViewModel.AddPaths(dialog.FileNames);
        }
    }

    private void AddFolder_OnClick(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog
        {
            Title = "Choose mod folder"
        };
        if (dialog.ShowDialog(this) == true)
        {
            ViewModel.AddPaths([dialog.FolderName]);
        }
    }

    private void RemoveSelected_OnClick(object sender, RoutedEventArgs e)
        => ViewModel.RemoveSources(SourcesListBox.SelectedItems.Cast<SourceEntry>().ToList());

    private void Clear_OnClick(object sender, RoutedEventArgs e)
        => ViewModel.ClearSources();

    private void ExcludeSelected_OnClick(object sender, RoutedEventArgs e)
        => ViewModel.ExcludeSources(SourcesListBox.SelectedItems.Cast<SourceEntry>().ToList());

    private void IncludeSelected_OnClick(object sender, RoutedEventArgs e)
        => ViewModel.IncludeSources(SourcesListBox.SelectedItems.Cast<SourceEntry>().ToList());

    private void BrowseOutput_OnClick(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog
        {
            Title = "Choose output directory",
            InitialDirectory = Directory.Exists(ViewModel.OutputDirectory) ? ViewModel.OutputDirectory : null
        };
        if (dialog.ShowDialog(this) == true)
        {
            ViewModel.OutputDirectory = dialog.FolderName;
        }
    }

    private void BrowseGameRoot_OnClick(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog
        {
            Title = "Choose game root / install folder",
            InitialDirectory = Directory.Exists(ViewModel.GameRoot) ? ViewModel.GameRoot : null
        };
        if (dialog.ShowDialog(this) == true)
        {
            ViewModel.GameRoot = dialog.FolderName;
        }
    }

    private void BrowseInstallerIcon_OnClick(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Multiselect = false,
            CheckFileExists = true,
            Filter = "Images|*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.ico;*.webp;*.tif;*.tiff;*.icns|All files|*.*",
            Title = "Choose installer icon"
        };
        if (dialog.ShowDialog(this) == true)
        {
            ViewModel.InstallerIconPath = dialog.FileName;
        }
    }

    private void ClearInstallerIcon_OnClick(object sender, RoutedEventArgs e)
        => ViewModel.ClearInstallerIcon();

    private void RevealOutput_OnClick(object sender, RoutedEventArgs e)
        => ViewModel.RevealOutputDirectory();

    private async void BuildMods_OnClick(object sender, RoutedEventArgs e)
    {
        try
        {
            await ViewModel.BuildAsync();
            MessageBox.Show(this, "Build completed.", "Mod Builder BW", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Build Error", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void DropZone_OnDragOver(object sender, DragEventArgs e)
    {
        e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
        e.Handled = true;
    }

    private void DropZone_OnDrop(object sender, DragEventArgs e)
    {
        if (!e.Data.GetDataPresent(DataFormats.FileDrop))
        {
            return;
        }

        if (e.Data.GetData(DataFormats.FileDrop) is string[] files)
        {
            ViewModel.AddPaths(files);
        }
    }
}
