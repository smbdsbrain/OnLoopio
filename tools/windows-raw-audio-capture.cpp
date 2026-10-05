// Host-only WASAPI RAW capture for synthetic AUX validation. No endpoint settings change.
// Build with MSVC/Windows SDK: cl /EHsc /std:c++17 /O2 /DNTDDI_VERSION=0x0A000000
// /D_WIN32_WINNT=0x0A00 windows-raw-audio-capture.cpp ole32.lib
#define NOMINMAX
#define INITGUID
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <functiondiscoverykeys_devpkey.h>
#include <wrl/client.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cwctype>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

using Microsoft::WRL::ComPtr;

static void check(HRESULT value) {
    if (FAILED(value))
        throw std::runtime_error("WASAPI error " + std::to_string(static_cast<unsigned long>(value)));
}

static std::wstring lower(std::wstring value) {
    std::transform(value.begin(), value.end(), value.begin(), towlower);
    return value;
}

struct ComLifetime {
    ComLifetime() { check(CoInitializeEx(nullptr, COINIT_MULTITHREADED)); }
    ~ComLifetime() { CoUninitialize(); }
};

struct StartedStream {
    IAudioClient2* client;
    explicit StartedStream(IAudioClient2* value) : client(value) { check(client->Start()); }
    ~StartedStream() { client->Stop(); }
};

#pragma pack(push, 1)
struct Header {
    char riff[4] = {'R', 'I', 'F', 'F'};
    DWORD size;
    char wave[4] = {'W', 'A', 'V', 'E'}, fmt[4] = {'f', 'm', 't', ' '};
    DWORD fmtSize = 16;
    WORD tag = 1, channels = 1;
    DWORD rate = 48000, bytes = 96000;
    WORD align = 2, bits = 16;
    char data[4] = {'d', 'a', 't', 'a'};
    DWORD dataSize;
};
#pragma pack(pop)
static_assert(sizeof(Header) == 44, "PCM header");

int wmain(int argc, wchar_t** argv) {
    try {
        if (argc != 4 || !argv[1][0])
            throw std::runtime_error("Usage: capture DEVICE_NAME_CONTAINS SECONDS OUTPUT.wav");
        double seconds = std::stod(argv[2]);
        if (!(seconds > 0 && seconds <= 600)) throw std::runtime_error("Duration must be 0..600 seconds");
        ComLifetime lifetime;
        ComPtr<IMMDeviceEnumerator> enumerator;
        check(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&enumerator)));
        ComPtr<IMMDeviceCollection> devices;
        check(enumerator->EnumAudioEndpoints(eCapture, DEVICE_STATE_ACTIVE, &devices));
        UINT count;
        check(devices->GetCount(&count));
        ComPtr<IMMDevice> selected;
        for (UINT n = 0; n < count; n++) {
            ComPtr<IMMDevice> device;
            check(devices->Item(n, &device));
            ComPtr<IPropertyStore> properties;
            check(device->OpenPropertyStore(STGM_READ, &properties));
            PROPVARIANT name;
            PropVariantInit(&name);
            check(properties->GetValue(PKEY_Device_FriendlyName, &name));
            bool match = name.vt == VT_LPWSTR && lower(name.pwszVal).find(lower(argv[1])) != std::wstring::npos;
            PropVariantClear(&name);
            if (match) {
                if (selected) throw std::runtime_error("Ambiguous input name");
                selected = device;
            }
        }
        if (!selected) throw std::runtime_error("No matching active input");
        ComPtr<IAudioClient2> client;
        check(selected->Activate(__uuidof(IAudioClient2), CLSCTX_ALL, nullptr,
                                reinterpret_cast<void**>(client.GetAddressOf())));
        AudioClientProperties properties = {sizeof(properties), FALSE, AudioCategory_Other, AUDCLNT_STREAMOPTIONS_RAW};
        check(client->SetClientProperties(&properties)); // Fail closed if RAW is refused.
        WAVEFORMATEX* native = nullptr;
        check(client->GetMixFormat(&native));
        std::unique_ptr<WAVEFORMATEX, decltype(&CoTaskMemFree)> format(native, CoTaskMemFree);
        WORD tag = native->wFormatTag;
        if (tag == WAVE_FORMAT_EXTENSIBLE) {
            if (native->cbSize < 22) throw std::runtime_error("Invalid extensible input format");
            const GUID pcm = {1, 0, 0x0010, {0x80, 0, 0, 0xaa, 0, 0x38, 0x9b, 0x71}};
            const GUID ieee = {3, 0, 0x0010, {0x80, 0, 0, 0xaa, 0, 0x38, 0x9b, 0x71}};
            GUID sub = reinterpret_cast<WAVEFORMATEXTENSIBLE*>(native)->SubFormat;
            tag = IsEqualGUID(sub, pcm) ? WAVE_FORMAT_PCM : IsEqualGUID(sub, ieee) ? WAVE_FORMAT_IEEE_FLOAT : 0;
        }
        bool floating = tag == WAVE_FORMAT_IEEE_FLOAT;
        if (native->nSamplesPerSec != 48000 || native->nChannels < 1 || native->nChannels > 8 ||
            (!floating && tag != WAVE_FORMAT_PCM) ||
            (floating ? native->wBitsPerSample != 32 : native->wBitsPerSample != 16 && native->wBitsPerSample != 32) ||
            native->nBlockAlign != native->nChannels * native->wBitsPerSample / 8)
            throw std::runtime_error("Unsupported input format (48 kHz PCM16/32 or float32 required)");
        check(client->Initialize(AUDCLNT_SHAREMODE_SHARED, 0, 1000000, 0, native, nullptr));
        ComPtr<IAudioCaptureClient> capture;
        check(client->GetService(IID_PPV_ARGS(&capture)));
        std::ofstream output(std::filesystem::path(argv[3]), std::ios::binary);
        if (!output) throw std::runtime_error("Cannot create capture");
        output.exceptions(std::ios::failbit | std::ios::badbit);
        Header header;
        header.dataSize = static_cast<DWORD>(seconds * 48000) * 2;
        header.size = header.dataSize + 36;
        output.write(reinterpret_cast<char*>(&header), sizeof(header));
        StartedStream stream(client.Get());
        std::cout << "RAW accepted rate=48000 native_bits=" << native->wBitsPerSample
                  << " native_channels=" << native->nChannels << " channel=0\n" << std::flush;
        UINT64 total = 0, target = header.dataSize / 2;
        unsigned discontinuities = 0;
        auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(static_cast<long long>((seconds + 10) * 1000));
        while (total < target) {
            if (std::chrono::steady_clock::now() > deadline) throw std::runtime_error("Capture timed out");
            UINT packet = 0;
            check(capture->GetNextPacketSize(&packet));
            if (!packet) { Sleep(2); continue; }
            BYTE* data = nullptr;
            UINT frames = 0;
            DWORD flags = 0;
            check(capture->GetBuffer(&data, &frames, &flags, nullptr, nullptr));
            if (total > 0 && (flags & AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY)) discontinuities++;
            UINT used = static_cast<UINT>(std::min<UINT64>(frames, target - total));
            std::vector<short> samples(used);
            for (UINT n = 0; n < used; n++) {
                double sample = 0;
                if (!(flags & AUDCLNT_BUFFERFLAGS_SILENT)) {
                    BYTE* at = data + n * native->nBlockAlign;
                    sample = floating ? *reinterpret_cast<float*>(at) : native->wBitsPerSample == 16 ?
                             *reinterpret_cast<short*>(at) / 32768.0 : *reinterpret_cast<int*>(at) / 2147483648.0;
                }
                if (!std::isfinite(sample)) sample = 0;
                samples[n] = static_cast<short>(std::lround(std::max(-1.0, std::min(32767.0 / 32768, sample)) * 32768));
            }
            output.write(reinterpret_cast<char*>(samples.data()), samples.size() * 2);
            total += used;
            check(capture->ReleaseBuffer(frames));
        }
        output.close();
        std::cout << "Saved frames=" << total << " discontinuities_after_first=" << discontinuities << "\n";
        if (discontinuities) throw std::runtime_error("Capture discontinuity; do not qualify this file");
        return 0;
    } catch (const std::exception& failed) {
        std::cerr << failed.what() << "\n";
        return 1;
    }
}
