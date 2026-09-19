#include "net/serializer.h"

#include <string>

namespace Serializer
{
	static uint16_t ReadInt16(const uint8_t* bytes)
	{
		uint8_t b0 = static_cast<uint8_t>(bytes[0]);
		uint8_t b1 = static_cast<uint8_t>(bytes[1]);

		return (b0 << 8) | b1;
	}

	static bool HasBytes(size_t offset, size_t needed, size_t total)
	{
		return (offset + needed <= total);
	}

	static uint16_t SafeReadInt16(const uint8_t* bytes, size_t offset, size_t total)
	{
		if (!HasBytes(offset, 2, total)) return 0;
		uint8_t b0 = static_cast<uint8_t>(bytes[offset]);
		uint8_t b1 = static_cast<uint8_t>(bytes[offset + 1]);
		return (b0 << 8) | b1;
	}

	static std::string SafeReadString(const uint8_t* bytes, size_t& offset, size_t total)
	{
		if (!HasBytes(offset, 2, total)) return "";
		uint16_t strLen = SafeReadInt16(bytes, offset, total);
		offset += 2;
		if (!HasBytes(offset, strLen, total)) {
			offset = total;
			return "";
		}
		std::string result((const char*)&bytes[offset], strLen);
		offset += strLen;
		return result;
	}

	static void WriteInt16(std::vector<uint8_t>& buffer, uint16_t value)
	{
		buffer.push_back((value >> 8) & 0xFF);
		buffer.push_back(value & 0xFF);
	}

	static void WriteInt32(std::vector<uint8_t>& buffer, uint32_t value)
	{
		buffer.push_back((value >> 24) & 0xFF);
		buffer.push_back((value >> 16) & 0xFF);
		buffer.push_back((value >> 8) & 0xFF);
		buffer.push_back(value & 0xFF);
	}

	static void WriteBool(std::vector<uint8_t>& buffer, bool value)
	{
		WriteInt32(buffer, value);
	}

	static void WriteString(std::vector<uint8_t>& buffer, std::string value)
	{
		WriteInt16(buffer, static_cast<uint16_t>(value.size()));
		buffer.insert(buffer.end(), value.begin(), value.end());
	}

	DeviceDescriptor DeserializeDeviceDescriptor(const uint8_t* bytes, size_t size)
	{
		if (!bytes || size < 4)
		{
			return DeviceDescriptor("", "", "udp", {}, {}, {});
		}

		size_t offset = 0;

		// Name is the first thing
		auto name = SafeReadString(bytes, offset, size);

		// The RTSP url follows after the name
		auto url = SafeReadString(bytes, offset, size);

		// Default protocol is udp. If it is a usb connection (via adb) switch to tcp
		std::string protocol = "udp";
		if (url.find("127.0.0.1") != std::string::npos)
		{
			protocol = "tcp";
		}

		// Represents how many resolutions are provided
		uint16_t frontResolutionCount = SafeReadInt16(bytes, offset, size);
		offset += 2;

		std::vector<DeviceDescriptor::Resolution> frontResolutions;
		for (int i = 0; i < frontResolutionCount && HasBytes(offset, 4, size); i++)
		{
			auto w = SafeReadInt16(bytes, offset, size);
			auto h = SafeReadInt16(bytes, offset + 2, size);
			frontResolutions.push_back(DeviceDescriptor::Resolution(w, h));
			offset += 4;
		}

		uint16_t backResolutionCount = SafeReadInt16(bytes, offset, size);
		offset += 2;

		std::vector<DeviceDescriptor::Resolution> backResolutions;
		for (int i = 0; i < backResolutionCount && HasBytes(offset, 4, size); i++)
		{
			auto w = SafeReadInt16(bytes, offset, size);
			auto h = SafeReadInt16(bytes, offset + 2, size);
			backResolutions.push_back(DeviceDescriptor::Resolution(w, h));
			offset += 4;
		}

		// How many filters
		uint16_t filterCount = SafeReadInt16(bytes, offset, size);
		offset += 2;

		Video::Filter::Registry filters;
		for (int i = 0; i < filterCount && offset < size; i++)
		{
			auto filterName = SafeReadString(bytes, offset, size);
			if (offset < size)
			{
				auto cat = static_cast<Video::Filter::Category>(bytes[offset]);
				offset++;
				filters[cat].push_back(filterName);
			}
		}

		return DeviceDescriptor(name, url, protocol, frontResolutions, backResolutions, filters);
	}

	std::vector<uint8_t> SerializeStreamOptions(const StreamOptions& state)
	{
		std::vector<uint8_t> buffer;
		buffer.reserve(512);

		// First byte of the buffer is reserved
		buffer.push_back(0);

		WriteInt32(buffer, state.fps);
		WriteInt32(buffer, state.resolution.first);
		WriteInt32(buffer, state.resolution.second);

		WriteBool(buffer, state.backCameraActive);
		
		WriteBool(buffer, state.adaptiveBitrate);
		WriteInt32(buffer, state.bitrate);
		WriteInt32(buffer, state.minBitrate);
		WriteInt32(buffer, state.maxBitrate);

		WriteBool(buffer, state.stabilizationEnabled);
		WriteBool(buffer, state.flashEnabled);
		WriteBool(buffer, state.h265Enabled);

		WriteInt16(buffer, static_cast<uint16_t>(state.filterSliderValues.size()));
		for (const auto& [name, value] : state.filterSliderValues)
		{
			WriteString(buffer, name);
			WriteInt32(buffer, value);
		}

		WriteString(buffer, state.activeEffectFilter);

		return buffer;
	}

	Connection::ErrorReport DeserializeErrorReport(const uint8_t* bytes, size_t size)
	{
		Connection::ErrorReport report;
		if (!bytes || size < 3) return report;

		size_t offset = 0;
		report.severity = bytes[offset++];
		report.error = SafeReadString(bytes, offset, size);
		report.description = SafeReadString(bytes, offset, size);
		
		return report;
	}
}