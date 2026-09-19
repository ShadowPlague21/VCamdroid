#include "net/server.h"

#include "logger.h"
#include "adb.h"
#include "net/serializer.h"

Server::Server(int port, const ConnectionListener& connectionListener) :
	port(port),
	connectionListener(connectionListener),
	acceptor(context)
{
	try {
		tcp::endpoint endpoint(tcp::v4(), port);
		acceptor.open(endpoint.protocol());

		acceptor.set_option(tcp::acceptor::reuse_address(true));

		acceptor.bind(endpoint);
		acceptor.listen();

		logger << "[SERVER] Initialized on port " << port << std::endl;
		
		adb::reverse(port);
		adb::forward(8554);
	}
	catch(std::exception& e)
	{
		logger << "[SERVER] CRITICAL INIT ERROR: " << e.what() << std::endl;
		throw;
	}
}

Server::HostInfo Server::GetHostInfo()
{
	std::string name = asio::ip::host_name();
	std::string ip_str = "127.0.0.1"; // Default fallback

	try {
		// Get the active network adapter by creating a dummy UDP connection 
		// and let the OS determine what network adapter will be used
		udp::resolver resolver(context);
		udp::socket socket(context);

		// This might throw if there is NO network adapter enabled
		socket.connect(udp::endpoint(asio::ip::address::from_string("8.8.8.8"), 80));
		ip_str = socket.local_endpoint().address().to_string();
		socket.close();
	}
	catch (std::exception& e) 
	{
		logger << "[SERVER] Warning: Could not detect local IP (" << e.what() << "). Defaulting to localhost.\n";
	}

	return { name, ip_str, std::to_string(port) };
}

void Server::Send(int id, const unsigned char* bytes, size_t size) const
{
	if (id >= 0 && id < connections.size())
	{
		connections[id]->Send(bytes, size);
	}
}

void Server::Start()
{
	if (!acceptor.is_open()) 
	{
		logger << "[SERVER] Cannot start: Acceptor is not open.\n";
		return;
	}

	try
	{
		TCPDoAccept();

		thread = std::thread([this]() {
			while (true)
			{
				try {
					context.run();
					break;
				}
				catch (std::exception& e) {
					logger << "[SERVER] CRITICAL EXCEPTION in IO Thread: " << e.what() << std::endl;
				}
				catch (...) {
					logger << "[SERVER] Unknown exception in IO Thread.\n";
				}
			}
		});
		
		logger << "[SERVER] Started" << std::endl;
	}
	catch (std::exception e)
	{
		logger << "[SERVER] Start failed: " << e.what() << "\n";
	}
}

void Server::Close()
{
	logger << "[SERVER] Closing...\n";

	asio::error_code ec;
	acceptor.close(ec);
	if (ec) logger << "[SERVER] Error closing acceptor: " << ec.message() << "\n";
	
	for (std::shared_ptr<Connection> conn : connections)
	{
		conn->Close(true);
	}

	context.stop();

	if (thread.joinable())
	{
		thread.join();
	}

	logger << "[SERVER] Closed.\n";

	adb::kill(port);
}

void Server::TCPDoAccept()
{
	acceptor.async_accept([this](asio::error_code ec, tcp::socket socket) {
		if (!ec)
		{
			logger << "[SERVER] Device connected: " << socket.remote_endpoint() << std::endl;

			auto sockPtr = std::make_shared<tcp::socket>(std::move(socket));
			auto bufferPtr = std::make_shared<std::vector<uint8_t>>();
			auto chunkPtr = std::make_shared<std::array<uint8_t, 512>>();

			ReadDeviceDescriptor(sockPtr, bufferPtr, chunkPtr);
		}
		else if (ec != asio::error::operation_aborted)
		{
			logger << "[SERVER] Accept Error: " << ec.message() << std::endl;
		}

		if (acceptor.is_open()) {
			TCPDoAccept();
		}
	});
}

void Server::ReadDeviceDescriptor(
	std::shared_ptr<tcp::socket> sockPtr,
	std::shared_ptr<std::vector<uint8_t>> bufferPtr,
	std::shared_ptr<std::array<uint8_t, 512>> chunkPtr)
{
	sockPtr->async_read_some(asio::buffer(*chunkPtr), [this, sockPtr, bufferPtr, chunkPtr](asio::error_code readEc, size_t bytesRead) {
		if (!readEc && bytesRead > 0)
		{
			bufferPtr->insert(bufferPtr->end(), chunkPtr->begin(), chunkPtr->begin() + bytesRead);

			if (bufferPtr->size() > 65536)
			{
				logger << "[SERVER] Device descriptor exceeded maximum allowed size (64KB), dropping connection" << std::endl;
				asio::error_code closeEc;
				sockPtr->close(closeEc);
				return;
			}

			size_t totalDescriptorSize = 0;
			if (Serializer::IsDeviceDescriptorComplete(bufferPtr->data(), bufferPtr->size(), totalDescriptorSize))
			{
				auto descriptor = Serializer::DeserializeDeviceDescriptor(bufferPtr->data(), totalDescriptorSize);

				std::vector<uint8_t> initialBytes;
				if (bufferPtr->size() > totalDescriptorSize)
				{
					initialBytes.assign(bufferPtr->begin() + totalDescriptorSize, bufferPtr->end());
				}

				auto conn = std::make_shared<Connection>(
					std::move(*sockPtr),
					descriptor,
					std::bind(&Server::OnConnectionDisconnected, this, std::placeholders::_1),
					std::bind(&Server::OnConnectionReportingError, this, std::placeholders::_1, std::placeholders::_2, std::placeholders::_3),
					std::move(initialBytes)
				);
				connections.push_back(conn);
				connectionListener.OnDeviceConnected(descriptor);
			}
			else
			{
				// Incomplete descriptor: fragment arrived, keep reading until all fields are complete
				ReadDeviceDescriptor(sockPtr, bufferPtr, chunkPtr);
			}
		}
		else
		{
			logger << "[SERVER] Failed to read initial device descriptor: " << (readEc ? readEc.message() : "EOF") << std::endl;
		}
	});
}

void Server::OnConnectionDisconnected(std::shared_ptr<Connection> connection)
{
	logger << "[SERVER] Device disconnected: " << connection->descriptor.name() << std::endl;

	connections.erase(std::remove(connections.begin(), connections.end(), connection), connections.end());
	connectionListener.OnDeviceDisconnected(connection->descriptor);
}

void Server::OnConnectionReportingError(std::shared_ptr<Connection> connection, const uint8_t* bytes, size_t size)
{
	auto report = Serializer::DeserializeErrorReport(bytes, size);
	connectionListener.OnDeviceErrorReported(connection->descriptor, report);
}
